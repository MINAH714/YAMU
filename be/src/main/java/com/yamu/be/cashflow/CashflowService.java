package com.yamu.be.cashflow;

import com.yamu.be.account.AccountQueryService;
import com.yamu.be.card.CardQueryService;
import com.yamu.be.card.UserCardRef;
import com.yamu.be.card.domain.CardIssuerStatus;
import com.yamu.be.global.common.ApiResponse.FieldErrorResponse;
import com.yamu.be.global.exception.BusinessException;
import com.yamu.be.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CashflowService {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    /** 조회 기간 상한(시작일·종료일 포함). 반복 회차를 기간만큼 펼치므로 큰 기간을 막는다. */
    private static final long MAX_QUERY_DAYS = 366;
    /** MySQL DATE가 담을 수 있는 범위. 밖의 날짜는 DB 오류(500)로 번지기 전에 400으로 거절한다. */
    private static final LocalDate MIN_DATE = LocalDate.of(1000, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);
    /**
     * 금액 상한(원). 명세에는 없다. 잔고예보가 여러 항목을 더할 때 넘치지 않고(Math.addExact 500 방지)
     * 앱(JS Number)에서도 정밀도를 잃지 않는 크기로 정했다.
     */
    static final long MAX_AMOUNT = 100_000_000_000L;
    static final String MAX_AMOUNT_MESSAGE = "금액은 1,000억 원 이하여야 합니다.";

    private final CashflowItemRepository items;
    private final CashflowOccurrenceRepository occurrences;
    private final CashflowCreateRequestRepository createRequests;
    private final AccountQueryService accounts;
    private final CardQueryService cards;
    private final Clock clock;

    CashflowService(CashflowItemRepository items, CashflowOccurrenceRepository occurrences,
            CashflowCreateRequestRepository createRequests, AccountQueryService accounts, CardQueryService cards,
            Clock clock) {
        this.items = items;
        this.occurrences = occurrences;
        this.createRequests = createRequests;
        this.accounts = accounts;
        this.cards = cards;
        this.clock = clock;
    }

    /** 예정 수입·지출 규칙과 첫 발생 건을 저장하고 저장된 규칙을 돌려준다. */
    @Transactional
    public Item create(Long authenticatedUserId, CreateCommand command) {
        return create(authenticatedUserId, command, null);
    }

    /**
     * {@link #create(Long, CreateCommand)}에 Idempotency-Key를 더한 등록(S15P31E103-358). 키가 있으면 이 사용자의 같은 키 기록을
     * 먼저 찾는다(2026-11-09 결정, Codex 검토 반영).
     * <ul>
     *   <li>내용이 다르면 409 IDEMPOTENCY_KEY_REUSED.</li>
     *   <li>내용이 같으면 새로 만들지 않고 그때 만든 규칙의 <b>지금 값</b>을 돌려준다(처음 응답의 복제가 아님). 그사이 고쳤으면 고친 값이다.</li>
     *   <li>그 규칙을 지웠으면 다시 만들지 않고 404 RESOURCE_NOT_FOUND다. 기록은 항목을 지워도 남는다.</li>
     * </ul>
     * 같은 키의 첫 요청 둘이 동시에 오면 늦은 쪽이 UNIQUE(uk_cashflow_create_request)에 걸린다({@link CashflowCreator}가 다시 부른다).
     *
     * @param requestKey UUID 형식으로 검사한 소문자 키. null이면 키 없는 등록이다
     */
    @Transactional
    public Item create(Long authenticatedUserId, CreateCommand command, String requestKey) {
        requireUser(authenticatedUserId);
        if (command == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        command = normalize(command);
        byte[] fingerprint = requestKey == null ? null : fingerprint(command);
        if (requestKey != null) {
            Optional<CashflowCreateRequest> previous =
                    createRequests.findByUserIdAndRequestKey(authenticatedUserId, requestKey);
            if (previous.isPresent()) {
                if (!MessageDigest.isEqual(previous.get().getRequestFingerprint(), fingerprint)) {
                    throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                }
                return items.findById(previous.get().getCashflowItemId())
                        .filter(item -> item.getUserId().equals(authenticatedUserId))
                        .map(Item::from)
                        .orElseThrow(CashflowService::notFound);
            }
        }
        CashflowItem.RecurrenceType recurrence = validate(command, today());
        requireOwned(authenticatedUserId, command.accountId(), command.userCardId());

        CashflowItem item = new CashflowItem(authenticatedUserId,
                command.accountId(), command.userCardId(), command.title(),
                command.direction(), command.amount(), command.scheduledDate(), recurrence,
                command.recurrenceEndDate(),
                dayOfMonthOf(recurrence, command.scheduledDate(), command.dayOfMonth(), null),
                command.merchantCategoryCode(), command.memo());
        item = items.save(item);
        occurrences.save(CashflowOccurrence.firstOf(item));
        if (requestKey != null) {
            createRequests.save(new CashflowCreateRequest(authenticatedUserId, requestKey, fingerprint, item.getId()));
        }
        return Item.from(item);
    }

    /**
     * 앞뒤 공백 정리 뒤의 등록 내용 SHA-256. 같은 키 재요청이 같은 내용인지 가린다. 칸마다 "길이:값;"(null은 "N;")으로 이어 붙여
     * 칸 경계가 값 안의 쉼표·등호와 섞이지 않게 한다(레코드 toString은 이스케이프하지 않아 서로 다른 요청이 같아질 수 있었음, Codex 검토).
     */
    static byte[] fingerprint(CreateCommand command) {
        StringBuilder text = new StringBuilder();
        for (Object value : new Object[] {command.title(), command.direction(), command.amount(),
                command.scheduledDate(), command.recurrenceType(), command.dayOfMonth(), command.dayOfWeek(),
                command.recurrenceEndDate(), command.accountId(), command.userCardId(),
                command.merchantCategoryCode(), command.memo()}) {
            if (value == null) {
                text.append("N;");
            } else {
                String part = value.toString();
                text.append(part.length()).append(':').append(part).append(';');
            }
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 예정 항목을 고치고 고친 회차나 규칙을 돌려준다. THIS_ONLY는 반복 항목의 한 회차만 고친다.
     * 같은 시리즈의 수정·삭제는 첫 규칙 행을 잠가 차례로 처리한다. 잠금을 기다린 뒤 앞 요청이 커밋한 값을 읽도록 READ COMMITTED로 연다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Entry update(Long authenticatedUserId, Long itemId, UpdateCommand command) {
        requireUser(authenticatedUserId);
        if (command == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (command.type() != null) {
            throw invalid("type", "수입·지출 유형은 바꿀 수 없습니다.");
        }
        LocalDate today = today();
        Series series = lockSeries(authenticatedUserId, itemId);
        CashflowItem rule = series.selected();
        if (command.scope() == UpdateScope.THIS_ONLY && rule.getRecurrenceType() != CashflowItem.RecurrenceType.NONE) {
            if (command.status() != null) {
                throw invalid("status", "이번 회차만 바꿀 때는 상태를 바꿀 수 없습니다.");
            }
            return updateOne(authenticatedUserId, rule, command, today);
        }
        if (command.status() != null) {
            changeStatus(series, command.status());
        }
        // 값을 하나도 보내지 않았으면(상태만 바꾸거나 빈 요청) 규칙을 나누지 않는다.
        if (!changesValues(command)) {
            return firstOccurrence(rule);
        }
        return updateFromHere(authenticatedUserId, series, command, today);
    }

    /**
     * 값을 바꾸지 않은 수정의 응답 줄(규칙의 첫 회차). 그 회차가 결제와 연결됐으면 목록과 같게 paid·matched를 채운다
     * (S15P31E103-393, 연결된 지난 회차가 있는 반복 항목의 상태만 바꿔도 목록과 응답의 연결 여부가 같게).
     */
    private Entry firstOccurrence(CashflowItem rule) {
        CashflowOccurrence row = occurrenceOf(rule, rule.getScheduledDate());
        if (row == null || occurrences.findMatchedIds(List.of(row.getId())).isEmpty()) {
            return new Entry(Item.from(rule), rule.getScheduledDate(), rule.getScheduledDate(), false);
        }
        boolean paid = !occurrences.findPaidIds(List.of(row.getId())).isEmpty();
        return new Entry(Item.from(rule), rule.getScheduledDate(), rule.getScheduledDate(), paid, true);
    }

    /** 탐지 후보(CANDIDATE)만 확인(CONFIRMED)이나 무시(IGNORED)로 바꿀 수 있다. 상태는 시리즈 전체가 같이 바뀐다. */
    private static void changeStatus(Series series, CashflowItem.Status to) {
        CashflowItem.Status from = series.selected().getStatus();
        if (from == to) {
            return;
        }
        if (from != CashflowItem.Status.CANDIDATE || to == CashflowItem.Status.CANDIDATE) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_CHANGE);
        }
        series.segments().forEach(segment -> segment.changeStatus(to));
    }

    private static boolean changesValues(UpdateCommand command) {
        return command.title() != null || command.amount() != null || command.date() != null
                || command.recurrence() != null || command.merchantCategoryCode() != null
                || command.accountId() != null || command.userCardId() != null || command.memo() != null;
    }

    /**
     * 예정 항목을 지운다. 탐지 후보는 지우지 않고 IGNORED로 바꾼다.
     * THIS_ONLY는 반복 항목의 한 회차를 EXCLUDED로 남기고(이미 지운 회차면 그대로 둔다),
     * 그 외는 고른 규칙이 속한 시리즈 전체에서 오늘 이후를 없앤다.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void delete(Long authenticatedUserId, Long itemId, DeleteScope scope, LocalDate occurrenceDate) {
        requireUser(authenticatedUserId);
        LocalDate today = today();
        Series series = lockSeries(authenticatedUserId, itemId);
        CashflowItem rule = series.selected();
        // 탐지 후보는 지우지 않고 무시로 남겨 같은 후보가 다시 탐지되지 않게 한다(명세).
        if (rule.getStatus() == CashflowItem.Status.CANDIDATE) {
            series.segments().forEach(segment -> segment.changeStatus(CashflowItem.Status.IGNORED));
            return;
        }
        if (scope == DeleteScope.THIS_ONLY && rule.getRecurrenceType() != CashflowItem.RecurrenceType.NONE) {
            CashflowOccurrence row = occurrenceOf(rule, occurrenceDate);
            if (row != null && row.getStatus() == CashflowOccurrence.Status.EXCLUDED) {
                return;
            }
            requireChangeable(row, occurrenceDate, today);
            (row == null ? occurrences.save(CashflowOccurrence.of(rule, occurrenceDate)) : row).exclude();
            return;
        }
        deleteFromToday(series, today);
    }

    /**
     * 고른 회차부터 시리즈의 이후를 새 값으로 덮는다. 고른 회차가 규칙의 첫 회차 이전이면 규칙을 제자리에서 바꾸고,
     * 아니면 기존 규칙을 그 전날로 닫고 새 규칙을 잇는다. 뒤에 이어진 규칙과 이후에 따로 고친 회차는 지운다.
     */
    private Entry updateFromHere(Long userId, Series series, UpdateCommand command, LocalDate today) {
        CashflowItem rule = series.selected();
        LocalDate applyDate = applyDate(rule, command.occurrenceDate(), today);
        LocalDate first = command.date() == null ? applyDate : command.date();
        // 날짜를 앞당기면 앞당긴 날부터 바뀐다.
        LocalDate cutoff = first.isBefore(applyDate) ? first : applyDate;
        if (first.isBefore(today)) {
            throw invalid("date", "날짜는 오늘 이후여야 합니다.");
        }
        CashflowItem previous = series.previous();
        // 앞 규칙은 나눌 때 항상 닫히므로 종료일이 있다. 겹치면 같은 날 회차가 두 번 나온다.
        if (previous != null && previous.getRecurrenceEndDate() != null
                && !first.isAfter(previous.getRecurrenceEndDate())) {
            throw invalid("date", "앞 일정이 끝나는 " + previous.getRecurrenceEndDate() + " 이후여야 합니다.");
        }

        Values changed = merge(Values.of(rule, first), command);
        RecurrenceChange recurrence = command.recurrence() != null ? command.recurrence()
                // 반복을 보내지 않으면 주기는 그대로 두고 시리즈의 끝을 이어받는다.
                : new RecurrenceChange(rule.getRecurrenceType(), null, null, seriesEnd(series, rule));
        CreateCommand values = normalize(new CreateCommand(changed.title(), rule.getDirection(), changed.amount(),
                first, recurrence.frequency(), recurrence.dayOfMonth(), recurrence.dayOfWeek(), recurrence.endDate(),
                changed.accountId(), changed.userCardId(), changed.merchantCategoryCode(), changed.memo()));
        CashflowItem.RecurrenceType recurrenceType = validate(values, today);
        // 날짜를 그대로 두면 매월 기준일도 이어받는다. 31일 반복을 말일로 당겨진 11/30에서 나눠도 12/31로 이어진다.
        // 앱이 고른 회차 날짜를 그대로 다시 보내도 바꾸지 않은 것으로 본다. 기준일만 바꾸려면 recurrence.dayOfMonth를 보낸다.
        boolean dateKept = command.date() == null || command.date().equals(applyDate);
        Integer dayOfMonth = dayOfMonthOf(recurrenceType, first, values.dayOfMonth(),
                dateKept ? Item.from(rule).recurrenceDayOfMonth() : null);
        requireOwned(userId, command.accountId(), command.userCardId());
        List<CashflowItem> affected = new ArrayList<>(series.following());
        affected.add(rule);
        requireNoMatchedFrom(affected, cutoff);

        removeFollowing(series);
        CashflowItem target;
        if (!cutoff.isAfter(rule.getScheduledDate())) {
            occurrences.deleteFrom(rule.getId(), MIN_DATE);
            rule.change(values.title(), values.amount(), values.scheduledDate(), recurrenceType,
                    values.recurrenceEndDate(), dayOfMonth, values.accountId(), values.userCardId(),
                    values.merchantCategoryCode(), values.memo());
            target = rule;
        } else {
            occurrences.deleteFrom(rule.getId(), cutoff);
            rule.closeBefore(cutoff);
            target = items.save(rule.successor(values.title(), values.amount(), values.scheduledDate(),
                    recurrenceType, values.recurrenceEndDate(), dayOfMonth, values.accountId(), values.userCardId(),
                    values.merchantCategoryCode(), values.memo()));
        }
        occurrences.save(CashflowOccurrence.firstOf(target));
        return new Entry(Item.from(target), target.getScheduledDate(), target.getScheduledDate(), false);
    }

    /**
     * 저장할 매월 기준일. 매월이 아니면 NULL, 요청에 반복일이 있으면 그 값, 이어받을 기준일이 있으면 그 값,
     * 아니면 첫 발생일의 날짜다.
     */
    private static Integer dayOfMonthOf(CashflowItem.RecurrenceType recurrence, LocalDate first, Integer requested,
            Integer inherited) {
        if (recurrence != CashflowItem.RecurrenceType.MONTHLY) {
            return null;
        }
        if (requested != null) {
            return requested;
        }
        return inherited != null ? inherited : first.getDayOfMonth();
    }

    /**
     * 반복을 보내지 않은 이후 전부 수정이 이어받을 종료일. 마지막 구간이 반복이면 그 종료일이고,
     * 1회 항목이면 그 날짜에서 끝난 시리즈라 그 날짜다(종료일 없음으로 이어받으면 끝난 시리즈가 무기한 되살아난다).
     */
    private static LocalDate seriesEnd(Series series, CashflowItem rule) {
        if (rule.getRecurrenceType() == CashflowItem.RecurrenceType.NONE) {
            return null;
        }
        CashflowItem last = series.last();
        return last.getRecurrenceType() == CashflowItem.RecurrenceType.NONE
                ? last.getScheduledDate() : last.getRecurrenceEndDate();
    }

    /**
     * 이후 전부 수정이 시작되는 회차. 1회 항목은 그 날짜, 반복 항목은 보낸 회차(오늘 이후여야 한다)나
     * 보내지 않았으면 오늘 이후 첫 회차다.
     */
    private static LocalDate applyDate(CashflowItem rule, LocalDate occurrenceDate, LocalDate today) {
        LocalDate applyDate;
        if (rule.getRecurrenceType() == CashflowItem.RecurrenceType.NONE) {
            applyDate = rule.getScheduledDate();
        } else if (occurrenceDate != null) {
            requireStorable(occurrenceDate, "occurrenceDate");
            if (occurrenceDates(Item.from(rule), occurrenceDate, occurrenceDate).isEmpty()) {
                throw invalid("occurrenceDate", "이 일정의 회차 날짜가 아닙니다.");
            }
            applyDate = occurrenceDate;
        } else {
            // 등록할 수 있는 반복(매주·매월)은 시작한 뒤 1년 안에 반드시 회차가 있다.
            // 아직 시작하지 않은 규칙은 1년보다 뒤에 시작할 수 있어 첫 발생일부터 찾는다.
            LocalDate from = rule.getScheduledDate().isAfter(today) ? rule.getScheduledDate() : today;
            applyDate = occurrenceDates(Item.from(rule), from, from.plusDays(MAX_QUERY_DAYS)).stream()
                    .findFirst().orElse(null);
        }
        if (applyDate == null || applyDate.isBefore(today)) {
            throw invalid("occurrenceDate", "지난 일정은 고칠 수 없습니다.");
        }
        return applyDate;
    }

    /**
     * 시리즈 전체에서 오늘 이후를 없앤다. 아직 시작하지 않은 규칙(첫 발생일이 오늘 이후)은 행째 지우고,
     * 시작한 반복 규칙은 어제로 닫는다. 지난 회차를 오늘 이후로 옮겨 둔 회차는 EXCLUDED로 바꾼다.
     */
    private void deleteFromToday(Series series, LocalDate today) {
        requireNoMatchedFrom(series.segments(), today);
        boolean changed = false;
        // 뒤 규칙이 앞 규칙을 가리키므로(FK) 뒤에서부터 지운다.
        for (int i = series.segments().size() - 1; i >= 0; i--) {
            CashflowItem segment = series.segments().get(i);
            if (!segment.getScheduledDate().isBefore(today)) {
                occurrences.deleteFrom(segment.getId(), MIN_DATE);
                items.delete(segment);
                changed = true;
                continue;
            }
            if (segment.getRecurrenceType() != CashflowItem.RecurrenceType.NONE
                    && (segment.getRecurrenceEndDate() == null || !segment.getRecurrenceEndDate().isBefore(today))) {
                occurrences.deleteFrom(segment.getId(), today);
                segment.closeBefore(today);
                changed = true;
            }
            for (CashflowOccurrence moved : occurrences.findMovedFromPast(segment.getId(), today)) {
                moved.exclude();
                changed = true;
            }
        }
        if (!changed) {
            throw invalid("cashflowItemId", "이미 지난 일정이라 지울 회차가 없습니다.");
        }
    }

    /** 고른 규칙 뒤에 이어진 규칙과 그 회차를 지운다. 뒤 규칙은 모두 컷오프 이후에 시작해 지난 회차가 없다. */
    private void removeFollowing(Series series) {
        List<CashflowItem> following = series.following();
        for (int i = following.size() - 1; i >= 0; i--) {
            occurrences.deleteFrom(following.get(i).getId(), MIN_DATE);
            items.delete(following.get(i));
        }
        // 지운 규칙의 DELETE가 새 규칙 INSERT보다 먼저 나가게 한다.
        items.flush();
    }

    /** 바꿀 범위에 결제와 연결된 회차가 있으면 409로 거절한다. */
    private void requireNoMatchedFrom(List<CashflowItem> rules, LocalDate from) {
        if (occurrences.countMatchedFrom(rules.stream().map(CashflowItem::getId).toList(), from) > 0) {
            throw new BusinessException(ErrorCode.PAID_OCCURRENCE_LOCKED);
        }
    }

    /** 반복 항목의 한 회차만 고친다. 이미 고친 회차는 그 값 위에, 처음 고치는 회차는 규칙 값 위에 보낸 필드만 바꾼다. */
    private Entry updateOne(Long userId, CashflowItem rule, UpdateCommand command, LocalDate today) {
        if (command.recurrence() != null) {
            throw invalid("recurrence", "이번 회차만 바꿀 때는 반복 규칙을 바꿀 수 없습니다.");
        }
        CashflowOccurrence row = occurrenceOf(rule, command.occurrenceDate());
        if (row != null && row.getStatus() == CashflowOccurrence.Status.EXCLUDED) {
            throw invalid("occurrenceDate", "삭제한 회차는 고칠 수 없습니다.");
        }
        requireChangeable(row, command.occurrenceDate(), today);
        Values base = row != null && row.isOverridden() ? Values.of(row) : Values.of(rule, command.occurrenceDate());
        Values changed = merge(base, command);
        if (changed.date().isBefore(today)) {
            throw invalid("date", "날짜는 오늘 이후여야 합니다.");
        }
        validate(new CreateCommand(changed.title(), rule.getDirection(), changed.amount(), changed.date(),
                CashflowItem.RecurrenceType.NONE, null, null, null, changed.accountId(), changed.userCardId(),
                changed.merchantCategoryCode(), changed.memo()), today);
        requireOwned(userId, command.accountId(), command.userCardId());

        if (row == null) {
            row = occurrences.save(CashflowOccurrence.of(rule, command.occurrenceDate()));
        }
        row.override(changed.date(), changed.title(), changed.amount(), changed.accountId(), changed.userCardId(),
                changed.merchantCategoryCode(), changed.memo());
        return new Entry(Item.from(rule).with(row), row.getOccurrenceDate(), row.getScheduledDate(), false);
    }

    /**
     * 고른 규칙이 속한 시리즈의 첫 규칙을 잠그고 시리즈 전체를 읽는다. 없거나 다른 사용자의 것이면 404다.
     * 같은 시리즈의 수정·삭제는 모두 첫 규칙을 잠그므로 차례로 처리된다. 잠금 뒤의 조회는 READ COMMITTED라 앞 요청의 결과를 본다.
     */
    private Series lockSeries(Long userId, Long itemId) {
        Set<Long> seen = new HashSet<>();
        Long headId = itemId;
        while (true) {
            CashflowItemRepository.Link link = items.findLink(headId, userId).orElseThrow(CashflowService::notFound);
            if (!seen.add(link.getId())) {
                throw new IllegalStateException("예정 항목 시리즈에 순환이 있다: " + itemId);
            }
            if (link.getPreviousCashflowItemId() == null) {
                break;
            }
            headId = link.getPreviousCashflowItemId();
        }
        CashflowItem head = items.findForUpdate(headId, userId).orElseThrow(CashflowService::notFound);
        List<CashflowItem> segments = new ArrayList<>(List.of(head));
        Set<Long> walked = new HashSet<>(Set.of(head.getId()));
        while (true) {
            List<CashflowItem> next = items.findByPreviousCashflowItemIdAndUserId(
                    segments.get(segments.size() - 1).getId(), userId);
            if (next.isEmpty()) {
                break;
            }
            if (next.size() > 1 || !walked.add(next.get(0).getId())) {
                throw new IllegalStateException("예정 항목 시리즈가 한 줄이 아니다: " + itemId);
            }
            segments.add(next.get(0));
        }
        for (int i = 0; i < segments.size(); i++) {
            if (segments.get(i).getId().equals(itemId)) {
                return new Series(List.copyOf(segments), i);
            }
        }
        // 잠금을 기다리는 동안 앞 요청이 고른 규칙을 지웠다.
        throw notFound();
    }

    /** 규칙의 실제 회차 날짜인지 확인하고 저장된 회차 행을 돌려준다. 저장된 행이 없으면 null이다. */
    private CashflowOccurrence occurrenceOf(CashflowItem rule, LocalDate occurrenceDate) {
        if (occurrenceDate == null) {
            throw invalid("occurrenceDate", "고를 회차의 날짜를 보내 주세요.");
        }
        requireStorable(occurrenceDate, "occurrenceDate");
        if (occurrenceDates(Item.from(rule), occurrenceDate, occurrenceDate).isEmpty()) {
            throw invalid("occurrenceDate", "이 일정의 회차 날짜가 아닙니다.");
        }
        return occurrences.findByCashflowItemIdAndOccurrenceDate(rule.getId(), occurrenceDate).orElse(null);
    }

    /** 지난 회차와 결제와 연결된 회차는 바꾸지 않는다. 날짜를 옮긴 회차는 옮긴 날짜로 지났는지 본다. */
    private void requireChangeable(CashflowOccurrence row, LocalDate occurrenceDate, LocalDate today) {
        LocalDate current = row != null && row.isOverridden() ? row.getScheduledDate() : occurrenceDate;
        if (current.isBefore(today)) {
            throw invalid("occurrenceDate", "지난 회차는 바꿀 수 없습니다.");
        }
        // 결제와 연결된 회차는 실제 결제 기록이 기준이라 예정 수정으로 바꾸지 않는다.
        if (row != null && !occurrences.findMatchedIds(List.of(row.getId())).isEmpty()) {
            throw new BusinessException(ErrorCode.PAID_OCCURRENCE_LOCKED);
        }
    }

    /**
     * 결제와 연결할 회차를 시리즈를 잠근 채 찾고, 저장된 행이 없으면 규칙 값으로 만들어 저장한다.
     * 같은 시리즈의 수정·삭제·다른 매칭과 차례로 처리된다. 호출자의 트랜잭션 안에서만 부른다.
     * 없거나 다른 사용자의 규칙은 404, 카드를 지정한 확정 예정 지출 회차가 아니면 400이다.
     */
    MatchTarget lockOccurrenceForMatch(Long userId, Long itemId, LocalDate occurrenceDate) {
        CashflowItem rule = lockSeries(userId, itemId).selected();
        CashflowOccurrence row = occurrenceOf(rule, occurrenceDate);
        requireMatchable(rule, row, occurrenceDate);
        if (row == null) {
            // 매칭 행이 회차 행을 FK로 가리키므로 ID가 필요하다.
            row = occurrences.saveAndFlush(CashflowOccurrence.of(rule, occurrenceDate));
        }
        return MatchTarget.of(rule, row, occurrenceDate);
    }

    /**
     * 매칭 상태·후보 조회용으로 회차를 잠그지 않고 읽는다. 저장된 행이 없으면 occurrenceId가 null이고 규칙 값을 쓴다.
     * 없거나 다른 사용자의 규칙은 404다. matchableOnly면 결제와 연결할 수 없는 회차를 400으로 거절한다.
     */
    MatchTarget readOccurrenceForMatch(Long userId, Long itemId, LocalDate occurrenceDate, boolean matchableOnly) {
        CashflowItem rule = items.findById(itemId).filter(found -> found.getUserId().equals(userId))
                .orElseThrow(CashflowService::notFound);
        CashflowOccurrence row = occurrenceOf(rule, occurrenceDate);
        if (matchableOnly) {
            requireMatchable(rule, row, occurrenceDate);
        }
        return MatchTarget.of(rule, row, occurrenceDate);
    }

    /** 카드를 지정한 확정 예정 지출의 빼지 않은 회차만 결제와 연결한다. 카드는 회차에 저장된 값이 있으면 그 값을 본다. */
    private static void requireMatchable(CashflowItem rule, CashflowOccurrence row, LocalDate occurrenceDate) {
        if (rule.getItemKind() != CashflowItem.ItemKind.SCHEDULED
                || rule.getDirection() != CashflowItem.Direction.EXPENSE) {
            throw invalid("cashflowItemId", "예정 지출만 결제와 연결할 수 있습니다.");
        }
        if (rule.getStatus() != CashflowItem.Status.CONFIRMED) {
            throw invalid("cashflowItemId", "확정한 예정 지출만 결제와 연결할 수 있습니다.");
        }
        if (row != null && row.getStatus() == CashflowOccurrence.Status.EXCLUDED) {
            throw invalid("occurrenceDate", "뺀 회차는 결제와 연결할 수 없습니다.");
        }
        if (MatchTarget.of(rule, row, occurrenceDate).userCardId() == null) {
            throw invalid("cashflowItemId", "결제 카드를 지정한 예정 지출만 결제와 연결할 수 있습니다.");
        }
    }

    /** 보낸 필드만 바꾼다. 업종 코드·메모의 빈 문자열은 지우기다. 계좌나 카드를 보내면 다른 하나는 비운다. */
    private static Values merge(Values base, UpdateCommand change) {
        if (change.accountId() != null && change.userCardId() != null) {
            throw invalid("userCardId", "계좌와 카드 중 하나만 선택해 주세요.");
        }
        Long accountId = base.accountId();
        Long userCardId = base.userCardId();
        if (change.accountId() != null) {
            accountId = change.accountId();
            userCardId = null;
        } else if (change.userCardId() != null) {
            accountId = null;
            userCardId = change.userCardId();
        }
        return new Values(change.title() == null ? base.title() : change.title().strip(),
                change.amount() == null ? base.amount() : change.amount(),
                change.date() == null ? base.date() : change.date(),
                accountId, userCardId,
                change.merchantCategoryCode() == null
                        ? base.merchantCategoryCode() : blankToNull(change.merchantCategoryCode()),
                change.memo() == null ? base.memo() : blankToNull(change.memo()));
    }

    /** 남의 것·없는 것·연결 해제·등록 해제는 BE 1 조회가 404 RESOURCE_NOT_FOUND로 던진다. */
    private void requireOwned(Long userId, Long accountId, Long userCardId) {
        if (accountId != null) {
            accounts.requireConnectedAccount(userId, accountId);
        }
        if (userCardId != null) {
            UserCardRef card = cards.requireRegisteredCard(userId, userCardId);
            // 카드사 정지·만료 카드로는 결제가 일어날 수 없어 예보를 틀리게 하므로 받지 않는다(추천도 정지 카드를 제외한다).
            if (card.issuerStatus() != CardIssuerStatus.NORMAL) {
                throw invalid("userCardId", "정지되었거나 만료된 카드는 결제 카드로 지정할 수 없습니다.");
            }
        }
    }

    /**
     * 조회 기간과 겹치는 사용자의 예정 항목을 발생일·항목 ID 순으로 돌려준다.
     * expand면 반복 규칙을 기간 안의 발생일마다 한 건씩 펼치고, 아니면 규칙 1건을 첫 발생일로 돌려준다.
     * from을 생략하면 한국 날짜 오늘, to를 생략하면 from을 포함한 30일째 날이다. 기간은 최대 366일이다.
     */
    @Transactional(readOnly = true)
    public List<Entry> list(Long authenticatedUserId, LocalDate from, LocalDate to,
            CashflowItem.Direction direction, CashflowItem.Status status, boolean expand) {
        requireUser(authenticatedUserId);
        LocalDate start = from == null ? today() : from;
        requireStorable(start, "from");
        if (to != null) {
            requireStorable(to, "to");
        }
        LocalDate end = to == null ? start.plusDays(29) : to;
        // to를 생략하면 from이 기본 종료일을 정하므로 from의 문제로 알린다.
        requireStorable(end, to == null ? "from" : "to");
        if (start.isAfter(end)) {
            throw invalid("to", "조회 종료일은 시작일보다 빠를 수 없습니다.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_QUERY_DAYS) {
            throw invalid("to", "조회 기간은 최대 " + MAX_QUERY_DAYS + "일입니다.");
        }
        CashflowItem.Status wanted = status == null ? CashflowItem.Status.CONFIRMED : status;
        List<Item> rules = items.findRulesForUser(authenticatedUserId, start, end, CashflowItem.ItemKind.SCHEDULED,
                CashflowItem.RecurrenceType.NONE, direction, wanted).stream().map(Item::from).toList();
        if (!expand) {
            return rules.stream().map(rule -> new Entry(rule, rule.scheduledDate(), rule.scheduledDate(), false))
                    .toList();
        }
        return expand(authenticatedUserId, rules, start, end, direction, wanted);
    }

    /**
     * 규칙을 기간 안의 회차로 펼치고, 저장된 회차로 덮는다.
     * 제외한 회차는 빼고, 이번 회차만 고친 회차와 실제 결제와 연결된 회차는 그 행의 값과 예정일로 보여 준다.
     * 날짜를 옮긴 회차는 옮긴 예정일로 기간 안팎을 가른다. 규칙 자체가 기간 밖이어도 옮겨 들어온 회차는 보여 준다.
     */
    private List<Entry> expand(Long userId, List<Item> rules, LocalDate start, LocalDate end,
            CashflowItem.Direction direction, CashflowItem.Status status) {
        List<CashflowOccurrence> rows = occurrences.findForUserBetween(userId, start, end);
        Set<Long> matched = rows.isEmpty() ? Set.of()
                : new HashSet<>(occurrences.findMatchedIds(rows.stream().map(CashflowOccurrence::getId).toList()));
        // 연결된 결제가 취소된 회차는 저장된 값을 그대로 쓰되(연결 해제 전까지 잠김) 다시 예정으로 센다.
        Set<Long> paid = matched.isEmpty() ? Set.of() : new HashSet<>(occurrences.findPaidIds(matched));
        Map<Slot, CashflowOccurrence> rowBySlot = new HashMap<>();
        for (CashflowOccurrence row : rows) {
            rowBySlot.put(new Slot(row.getCashflowItemId(), row.getOccurrenceDate()), row);
        }

        List<Entry> entries = new ArrayList<>();
        for (Item rule : rules) {
            for (LocalDate date : occurrenceDates(rule, start, end)) {
                CashflowOccurrence row = rowBySlot.get(new Slot(rule.id(), date));
                // 저장된 값을 쓰는 회차는 아래에서 옮긴 예정일 기준으로 따로 넣는다.
                if (row == null || !(row.getStatus() == CashflowOccurrence.Status.EXCLUDED
                        || usesRowValues(row, matched))) {
                    entries.add(new Entry(rule, date, date, false));
                }
            }
        }

        Map<Long, Item> ruleById = new HashMap<>();
        rules.forEach(rule -> ruleById.put(rule.id(), rule));
        List<CashflowOccurrence> fixed = rows.stream()
                .filter(row -> row.getStatus() == CashflowOccurrence.Status.CONFIRMED && usesRowValues(row, matched))
                .filter(row -> !row.getScheduledDate().isBefore(start) && !row.getScheduledDate().isAfter(end))
                .toList();
        // 기간 밖 규칙에서 옮겨 들어온 회차는 규칙을 따로 읽고 목록 조건(종류·유형·상태)을 똑같이 건다.
        Set<Long> missing = new HashSet<>();
        fixed.forEach(row -> {
            if (!ruleById.containsKey(row.getCashflowItemId())) {
                missing.add(row.getCashflowItemId());
            }
        });
        if (!missing.isEmpty()) {
            items.findAllById(missing).stream()
                    .filter(rule -> rule.getUserId().equals(userId)
                            && rule.getItemKind() == CashflowItem.ItemKind.SCHEDULED
                            && (direction == null || rule.getDirection() == direction)
                            && rule.getStatus() == status)
                    .forEach(rule -> ruleById.put(rule.getId(), Item.from(rule)));
        }
        for (CashflowOccurrence row : fixed) {
            Item rule = ruleById.get(row.getCashflowItemId());
            if (rule != null) {
                entries.add(new Entry(rule.with(row), row.getOccurrenceDate(), row.getScheduledDate(),
                        paid.contains(row.getId()), matched.contains(row.getId())));
            }
        }
        entries.sort(Comparator.comparing(Entry::date).thenComparing(entry -> entry.item().id())
                .thenComparing(Entry::occurrenceDate));
        return entries;
    }

    /** 이번 회차만 고쳤거나 실제 결제와 연결된 회차는 규칙 값이 바뀌어도 그 행의 값을 쓴다. */
    /**
     * 확인 전 탐지 후보 수(명세 candidateCount). 값을 고쳐 규칙이 나뉜 후보는 시리즈 마지막 규칙만 센다.
     *
     * @param authenticatedUserId 로그인 사용자
     * @return 후보 수
     */
    @Transactional(readOnly = true)
    public long candidateCount(Long authenticatedUserId) {
        requireUser(authenticatedUserId);
        return items.countSeries(authenticatedUserId, CashflowItem.ItemKind.SCHEDULED, CashflowItem.Status.CANDIDATE);
    }

    private static boolean usesRowValues(CashflowOccurrence row, Set<Long> matched) {
        return row.isOverridden() || matched.contains(row.getId());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(KOREA));
    }

    /**
     * 규칙의 발생일 중 from~to(포함)와 반복 종료일 안에 드는 날짜를 차례로 돌려준다.
     * n번째 회차는 첫 발생일에 반복 단위를 n번 더한 날이다. 매월은 n달 뒤 그달의 기준일이라,
     * 31일은 2/28 → 3/31처럼 없는 달만 말일로 맞춰지고 11/30에서 시작한 기준일 31 규칙도 12/31로 이어진다.
     */
    static List<LocalDate> occurrenceDates(Item rule, LocalDate from, LocalDate to) {
        LocalDate first = rule.scheduledDate();
        LocalDate last = rule.recurrenceEndDate() != null && rule.recurrenceEndDate().isBefore(to)
                ? rule.recurrenceEndDate() : to;
        ChronoUnit unit = switch (rule.recurrenceType()) {
            case NONE -> null;
            case DAILY -> ChronoUnit.DAYS;
            case WEEKLY -> ChronoUnit.WEEKS;
            case MONTHLY -> ChronoUnit.MONTHS;
            case YEARLY -> ChronoUnit.YEARS;
        };
        List<LocalDate> dates = new ArrayList<>();
        if (unit == null) {
            if (!first.isBefore(from) && !first.isAfter(last)) {
                dates.add(first);
            }
            return dates;
        }
        // between은 first에 더해도 from을 넘지 않는 최대 회차 수라, 그 회차부터 보면 앞 회차를 건너뛴다.
        // 매월 기준일 회차는 first.plusMonths(n) 이상이고 같은 달이라, 아래 시작점은 매월에도 그대로 맞다.
        for (long n = Math.max(0, unit.between(first, from)); ; n++) {
            LocalDate date = unit == ChronoUnit.MONTHS ? nthMonth(first, n, rule.recurrenceDayOfMonth())
                    : first.plus(n, unit);
            if (date.isAfter(last)) {
                return dates;
            }
            if (!date.isBefore(from)) {
                dates.add(date);
            }
        }
    }

    /** 첫 발생일에서 n달 뒤 그달의 기준일. 그달에 없으면 말일이다. */
    private static LocalDate nthMonth(LocalDate first, long n, int dayOfMonth) {
        YearMonth month = YearMonth.from(first).plusMonths(n);
        return month.atDay(Math.min(dayOfMonth, month.lengthOfMonth()));
    }

    /** 이름·업종 코드·메모의 앞뒤 공백(유니코드 공백 포함)을 없애고, 빈 업종 코드·메모는 NULL로 바꾼다. */
    private static CreateCommand normalize(CreateCommand command) {
        return new CreateCommand(command.title() == null ? null : command.title().strip(),
                command.direction(), command.amount(), command.scheduledDate(), command.recurrenceType(),
                command.dayOfMonth(), command.dayOfWeek(), command.recurrenceEndDate(),
                command.accountId(), command.userCardId(),
                blankToNull(command.merchantCategoryCode()), blankToNull(command.memo()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static CashflowItem.RecurrenceType validate(CreateCommand command, LocalDate today) {
        String title = command.title();
        if (command.direction() == null) {
            throw invalid("type", "수입·지출 유형을 선택해 주세요.");
        }
        if (title == null || title.isBlank()) {
            throw invalid("title", "이름을 입력해 주세요.");
        }
        if (title.length() > 30) {
            throw invalid("title", "이름은 30자 이하로 입력해 주세요.");
        }
        if (command.amount() == null || command.amount() <= 0) {
            throw invalid("amount", "금액은 1원 이상이어야 합니다.");
        }
        if (command.amount() > MAX_AMOUNT) {
            throw invalid("amount", MAX_AMOUNT_MESSAGE);
        }
        if (command.scheduledDate() == null) {
            throw invalid("date", "첫 발생일을 입력해 주세요.");
        }
        if (command.scheduledDate().isBefore(today)) {
            throw invalid("date", "첫 발생일은 오늘 이후여야 합니다.");
        }
        requireStorable(command.scheduledDate(), "date");

        CashflowItem.RecurrenceType recurrence = command.recurrenceType() == null
                ? CashflowItem.RecurrenceType.NONE : command.recurrenceType();
        if (recurrence != CashflowItem.RecurrenceType.NONE
                && recurrence != CashflowItem.RecurrenceType.WEEKLY
                && recurrence != CashflowItem.RecurrenceType.MONTHLY) {
            throw invalid("recurrence.frequency", "반복은 WEEKLY 또는 MONTHLY만 지원합니다.");
        }
        if (command.recurrenceEndDate() != null) {
            if (recurrence == CashflowItem.RecurrenceType.NONE) {
                throw invalid("recurrence.endDate", "반복하지 않는 항목에는 종료일을 지정할 수 없습니다.");
            }
            if (command.recurrenceEndDate().isBefore(command.scheduledDate())) {
                throw invalid("recurrence.endDate", "반복 종료일은 첫 발생일보다 빠를 수 없습니다.");
            }
            requireStorable(command.recurrenceEndDate(), "recurrence.endDate");
        }
        // 첫 발생일이 그달 말일이면 더 큰 반복일도 받는다(2/28 시작 31일 반복 → 3/31).
        if (command.dayOfMonth() != null && (recurrence != CashflowItem.RecurrenceType.MONTHLY
                || command.dayOfMonth() < 1 || command.dayOfMonth() > 31
                || !nthMonth(command.scheduledDate(), 0, command.dayOfMonth()).equals(command.scheduledDate()))) {
            throw invalid("recurrence.dayOfMonth", "매월 반복일은 첫 발생일의 날짜와 같아야 합니다(말일 시작이면 더 큰 날도 됩니다).");
        }
        if (command.dayOfWeek() != null && (recurrence != CashflowItem.RecurrenceType.WEEKLY
                || command.dayOfWeek() != command.scheduledDate().getDayOfWeek())) {
            throw invalid("recurrence.dayOfWeek", "매주 반복 요일은 첫 발생일의 요일과 같아야 합니다.");
        }

        if (command.merchantCategoryCode() != null && command.merchantCategoryCode().length() > 64) {
            throw invalid("merchantCategoryCode", "가맹점 업종 코드는 64자 이하여야 합니다.");
        }
        if (command.memo() != null && command.memo().length() > 100) {
            throw invalid("memo", "메모는 100자 이하로 입력해 주세요.");
        }
        if (command.accountId() != null && command.accountId() <= 0) {
            throw invalid("accountId", "올바른 계좌를 선택해 주세요.");
        }
        if (command.userCardId() != null && command.userCardId() <= 0) {
            throw invalid("userCardId", "올바른 카드를 선택해 주세요.");
        }
        // 잔고예보가 귀속 계좌가 없는 항목을 임의 배분하지 않으므로 출금·입금 경로를 하나로 정한다.
        if (command.direction() == CashflowItem.Direction.INCOME && command.userCardId() != null) {
            throw invalid("userCardId", "수입에는 카드를 지정할 수 없습니다.");
        }
        if (command.accountId() == null && command.userCardId() == null) {
            throw invalid("accountId", "입출금 계좌나 결제 카드 중 하나를 선택해 주세요.");
        }
        if (command.accountId() != null && command.userCardId() != null) {
            throw invalid("userCardId", "계좌와 카드 중 하나만 선택해 주세요.");
        }
        return recurrence;
    }

    private static void requireStorable(LocalDate date, String field) {
        if (date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE)) {
            throw invalid(field, "날짜는 " + MIN_DATE + "부터 " + MAX_DATE + " 사이여야 합니다.");
        }
    }

    static BusinessException invalid(String field, String reason) {
        return new BusinessException(ErrorCode.INVALID_REQUEST,
                Map.of("fieldErrors", List.of(new FieldErrorResponse(field, reason))));
    }

    static BusinessException notFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    static void requireUser(Long authenticatedUserId) {
        if (authenticatedUserId == null || authenticatedUserId <= 0) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    public record CreateCommand(String title, CashflowItem.Direction direction, Long amount,
            LocalDate scheduledDate, CashflowItem.RecurrenceType recurrenceType,
            Integer dayOfMonth, DayOfWeek dayOfWeek, LocalDate recurrenceEndDate,
            Long accountId, Long userCardId, String merchantCategoryCode, String memo) {}

    public enum UpdateScope { THIS_AND_FUTURE, THIS_ONLY }

    public enum DeleteScope { ALL, THIS_ONLY }

    /** 수정 요청. null인 필드는 그대로 둔다. type은 바꿀 수 없어 보내면 거절한다. */
    public record UpdateCommand(CashflowItem.Direction type, String title, Long amount, LocalDate date,
            RecurrenceChange recurrence, String merchantCategoryCode, Long accountId, Long userCardId,
            String memo, CashflowItem.Status status, UpdateScope scope, LocalDate occurrenceDate) {}

    /** 반복 규칙을 통째로 바꿀 때의 새 규칙. endDate를 생략하면 종료일이 없다. */
    public record RecurrenceChange(CashflowItem.RecurrenceType frequency, Integer dayOfMonth,
            DayOfWeek dayOfWeek, LocalDate endDate) {}

    /** previous_cashflow_item_id로 이어진 규칙들(첫 규칙부터 순서대로)과 그중 고른 규칙의 위치. */
    private record Series(List<CashflowItem> segments, int selectedIndex) {
        CashflowItem selected() {
            return segments.get(selectedIndex);
        }

        CashflowItem previous() {
            return selectedIndex == 0 ? null : segments.get(selectedIndex - 1);
        }

        List<CashflowItem> following() {
            return segments.subList(selectedIndex + 1, segments.size());
        }

        CashflowItem last() {
            return segments.get(segments.size() - 1);
        }
    }

    /** 회차 하나를 정하는 값. 규칙 기본값이나 이번 회차만 고친 값이다. */
    private record Values(String title, Long amount, LocalDate date, Long accountId, Long userCardId,
            String merchantCategoryCode, String memo) {
        static Values of(CashflowOccurrence row) {
            return new Values(row.getTitle(), row.getAmount(), row.getScheduledDate(), row.getAccountId(),
                    row.getUserCardId(), row.getMerchantCategoryCode(), row.getMemo());
        }

        static Values of(CashflowItem rule, LocalDate date) {
            return new Values(rule.getTitle(), rule.getAmount(), date, rule.getAccountId(), rule.getUserCardId(),
                    rule.getMerchantCategoryCode(), rule.getMemo());
        }
    }

    /**
     * 목록의 한 줄. occurrenceDate는 규칙이 정한 원래 회차(이번 회차 수정·삭제 때 식별값), date는 실제 예정일이다.
     * 이번 회차만 날짜를 옮기지 않았으면 둘이 같다. paid는 취소되지 않은 실제 결제와 연결된 회차라 잔고예보가 예정 차감에서 빼야 함을 뜻한다.
     * matched는 결제와의 연결이 있는지다(S15P31E103-393). 연결한 결제가 취소돼도 연결을 풀기 전까지 true라 paid보다 넓다.
     */
    public record Entry(Item item, LocalDate occurrenceDate, LocalDate date, boolean paid, boolean matched) {

        /** 연결 여부를 따로 주지 않으면 paid와 같다(취소 안 된 결제와 연결됐으면 연결도 있다). */
        public Entry(Item item, LocalDate occurrenceDate, LocalDate date, boolean paid) {
            this(item, occurrenceDate, date, paid, paid);
        }
    }

    private record Slot(Long cashflowItemId, LocalDate occurrenceDate) {}

    /**
     * 결제와 연결할(또는 연결된) 회차. occurrenceId는 저장된 회차 행이 없으면 null이다.
     * 값은 저장된 행이 있으면 그 행의 값(이번 회차만 고친 값 포함), 없으면 규칙의 값이다.
     */
    record MatchTarget(Long cashflowItemId, Long occurrenceId, LocalDate occurrenceDate, LocalDate scheduledDate,
            Long amount, Long userCardId, String merchantCategoryCode) {
        static MatchTarget of(CashflowItem rule, CashflowOccurrence row, LocalDate occurrenceDate) {
            Values values = row != null ? Values.of(row) : Values.of(rule, occurrenceDate);
            return new MatchTarget(rule.getId(), row != null ? row.getId() : null, occurrenceDate, values.date(),
                    values.amount(), values.userCardId(), values.merchantCategoryCode());
        }
    }

    /**
     * 예정 규칙. recurrenceDayOfMonth는 매월 반복의 기준일(그달에 없으면 말일)이고 매월이 아니면 null이다.
     * DB에 기준일이 없는 매월 행(칸 추가 전)은 첫 발생일의 날짜로 채운다.
     */
    public record Item(Long id, Long accountId, Long userCardId, String title,
            CashflowItem.Direction direction, Long amount, LocalDate scheduledDate,
            CashflowItem.RecurrenceType recurrenceType, LocalDate recurrenceEndDate, Integer recurrenceDayOfMonth,
            String merchantCategoryCode, CashflowItem.Status status, String memo, CashflowItem.Source source) {
        static Item from(CashflowItem item) {
            Integer dayOfMonth = item.getRecurrenceType() != CashflowItem.RecurrenceType.MONTHLY ? null
                    : item.getRecurrenceDayOfMonth() != null ? item.getRecurrenceDayOfMonth()
                    : Integer.valueOf(item.getScheduledDate().getDayOfMonth());
            return new Item(item.getId(), item.getAccountId(), item.getUserCardId(),
                    item.getTitle(), item.getDirection(), item.getAmount(),
                    item.getScheduledDate(), item.getRecurrenceType(), item.getRecurrenceEndDate(), dayOfMonth,
                    item.getMerchantCategoryCode(), item.getStatus(), item.getMemo(), item.getSource());
        }

        /** 규칙의 ID·반복·상태는 두고 회차에 저장된 값으로 바꾼다. */
        Item with(CashflowOccurrence row) {
            return new Item(id, row.getAccountId(), row.getUserCardId(), row.getTitle(), direction,
                    row.getAmount(), scheduledDate, recurrenceType, recurrenceEndDate, recurrenceDayOfMonth,
                    row.getMerchantCategoryCode(), status, row.getMemo(), source);
        }
    }
}
