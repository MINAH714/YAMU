package com.yamu.be.seed.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yamu.be.global.calendar.KoreanBusinessDayCalendar;
import com.yamu.be.payment.bank.BankDirection;
import com.yamu.be.payment.billing.BillingCycle;
import java.time.*;
import java.util.*;

/** Fixed, reproducible QA life history. No bank/DB calls and no residual balancing purchase. */
public final class QaLifeScenario {
    public static final LocalDate BASE = LocalDate.of(2026, 11, 16);
    public static final LocalDate FROM = LocalDate.of(2025, 12, 1);
    private static final long[] START = {1_050_000, 900_000, 300_000};
    private QaLifeScenario() {}

    public static long startingBalance(int account) { return START[account]; }
    public static KoreanBusinessDayCalendar calendar() {
        return new KoreanBusinessDayCalendar(year -> {
            // This fixed scenario uses only November/December billing dates in 2025.
            if (year == 2025) return Optional.of(Set.of(LocalDate.of(2025,12,25)));
            try (var input = QaLifeScenario.class.getResourceAsStream("/holidays/" + year + ".json")) {
                if (input == null) return Optional.empty();
                var dates = new HashSet<LocalDate>();
                for (var row : new ObjectMapper().readTree(input).path("holidays"))
                    dates.add(LocalDate.parse(row.path("date").asText()));
                return Optional.of(dates);
            } catch (Exception e) { throw new IllegalStateException("Holiday fixture unavailable"); }
        });
    }

    public static List<QaHistoryPlan.Payment> payments(LocalDate base) {
        QaSeedSpec.require(BASE.equals(base), "LIFE scenario has a fixed reference date");
        var out = new ArrayList<QaHistoryPlan.Payment>();
        // November 2025 supports the first complete December bill. Existing March-November purchases stay unchanged.
        for (var ym = YearMonth.of(2025,11); ym.isBefore(YearMonth.of(2026,11)); ym = ym.plusMonths(1)) {
            int month = ym.getMonthValue();
            int week = 0;
            for (var day = ym.atDay(1); YearMonth.from(day).equals(ym); day = day.plusDays(1)) {
                if (day.getDayOfWeek() == DayOfWeek.SATURDAY) {
                    add(out, 0, day, "EMART", 62_000 + (week++ % 4) * 4_000 + (month % 3) * 3_000);
                }
                if (day.getDayOfWeek() == DayOfWeek.TUESDAY || day.getDayOfWeek() == DayOfWeek.THURSDAY)
                    add(out, 0, day, "YAMU_TEST_RESTAURANT", day.getDayOfMonth() % 2 == 0 ? 12_500 : 14_500);
                if (day.getDayOfWeek() == DayOfWeek.WEDNESDAY)
                    add(out, 3, day, "YAMU_TEST_RESTAURANT", 18_000 + (week % 3) * 2_000);
            }
            add(out, 0, ym.atDay(8), "COUPANG", 278_000 + (month % 3) * 10_000); // clothing / footwear
            add(out, 0, ym.atDay(24), "COUPANG", 104_000 + (month % 2) * 9_000); // household goods
            add(out, 3, ym.atDay(6), "GS25", 18_000);
            add(out, 3, ym.atDay(22), "GS25", 21_000);
            add(out, 1, ym.atDay(5), "COUPANG", (month==10?179_000:189_000) + (month % 3) * 10_000);
            add(out, 1, ym.atDay(12), "COUPANG", 79_000);
            add(out, 1, ym.atDay(25), "COUPANG", 149_000);
            add(out, 1, ym.atDay(9), "YAMU_TEST_RESTAURANT", 45_000);
            add(out, 1, ym.atDay(23), "YAMU_TEST_RESTAURANT", 45_000);
            add(out, 2, ym.atDay(7), "EMART", 112_000);
            add(out, 2, ym.atDay(19), "EMART", 112_000 + (month % 2) * 8_000);
            add(out, 2, ym.atDay(10), "SKT", 65_000);
            add(out, 2, ym.atDay(17), "YAMU_TEST_RESTAURANT", 88_000);
            add(out, 2, ym.atDay(21), "COUPANG", 13_500); // recurring online service
        }
        // Seasonal purchases only in the newly added months; no balancing transactions.
        add(out, 0, LocalDate.of(2025,12,20), "COUPANG", 96_000); // year-end gifts
        add(out, 1, LocalDate.of(2025,12,24), "YAMU_TEST_RESTAURANT", 82_000); // year-end dinner
        add(out, 0, LocalDate.of(2026,1,18), "COUPANG", 109_000); // winter household supplies
        add(out, 1, LocalDate.of(2026,2,9), "COUPANG", 128_000); // holiday gifts
        add(out, 2, LocalDate.of(2026,2,16), "YAMU_TEST_RESTAURANT", 124_000); // family meal
        add(out, 1, LocalDate.of(2026,7,11), "COUPANG", 900_000); // appliance, lump sum
        // Current month: 210,000 / 60,000 / 240,000 / 180,000. No 11/16 approvals.
        for (int[] r : new int[][]{{0,3,32000},{0,7,78000},{0,10,24000},{0,14,76000},
                {3,4,18000},{3,8,22000},{3,13,20000},{1,5,129000},{1,12,111000},
                {2,6,65000},{2,9,88000},{2,14,27000}})
            add(out,r[0],LocalDate.of(2026,11,r[1]),r[0]==3?(r[1]==8?"GS25":"YAMU_TEST_RESTAURANT"):"COUPANG",r[2]);
        out.sort(Comparator.comparing(QaHistoryPlan.Payment::date).thenComparingInt(QaHistoryPlan.Payment::slot));
        return List.copyOf(out);
    }

    private static void add(List<QaHistoryPlan.Payment> out,int card,LocalDate day,String brand,long amount) {
        int slot = (int)out.stream().filter(p -> p.date().equals(day)).count();
        out.add(new QaHistoryPlan.Payment(card, day, slot, brand, amount, "APPROVED", 1, 0));
    }

    public record Bill(int card, YearMonth month, LocalDate due, long amount) {}
    public static List<Bill> bills() {
        var sums = new TreeMap<String, Long>();
        var cycle = new BillingCycle(15,14);
        for (var p : payments(BASE)) if (p.card()==1 || p.card()==2) {
            var month=cycle.cycleFor(p.date()).billingMonth();
            sums.merge(p.card()+":"+month,QaLifeBenefits.billed(p),Math::addExact);
        }
        var calendar=calendar();
        return sums.entrySet().stream().map(e -> {
            var parts=e.getKey().split(":"); var month=YearMonth.parse(parts[1]);
            return new Bill(Integer.parseInt(parts[0]),month,calendar.businessDayOnOrAfter(month.atDay(15)),e.getValue());
        }).toList();
    }

    public static List<QaAssetHistoryPlan.Event> events(int account) {
        var events = new ArrayList<QaAssetHistoryPlan.Event>();
        for (var p : payments(BASE)) if (!p.date().isBefore(FROM) && (p.card()==0 && account==0 || p.card()==3 && account==2))
            events.add(new QaAssetHistoryPlan.Event("30-CHECK-"+p.card()+"-"+p.date()+"-"+p.slot(),p.date(),BankDirection.WITHDRAWAL,QaLifeBenefits.approved(p)));
        if (account==1) for(var bill:bills()) if(!bill.due().isBefore(FROM) && bill.due().isBefore(BASE))
            events.add(new QaAssetHistoryPlan.Event("30-BILL-"+bill.card()+"-"+bill.month(),bill.due(),BankDirection.WITHDRAWAL,bill.amount()));
        for(var ym=YearMonth.from(FROM);ym.isBefore(YearMonth.from(BASE));ym=ym.plusMonths(1)) {
            if(account==0) {
                events.add(new QaAssetHistoryPlan.Event("00-SALARY-"+ym,ym.atDay(26),BankDirection.DEPOSIT,2_500_000));
                events.add(new QaAssetHistoryPlan.Event("10-RENT-"+ym,ym.atDay(18),BankDirection.WITHDRAWAL,700_000));
            }
            // Regular allocation, never an external balancing income/expense. Pairs have equal IDs/amounts.
            if(account==0 || account==1) events.add(new QaAssetHistoryPlan.Event("20-TRANSFER-SH-"+ym,ym.atDay(26),
                    account==0?BankDirection.WITHDRAWAL:BankDirection.DEPOSIT,900_000));
            if(account==0 || account==2) events.add(new QaAssetHistoryPlan.Event("21-TRANSFER-IBK-"+ym,ym.atDay(26),
                    account==0?BankDirection.WITHDRAWAL:BankDirection.DEPOSIT,ym.equals(YearMonth.of(2026,10))?300_000:130_000)); // November check-card budget
        }
        return List.copyOf(events);
    }
    public static QaAssetHistoryPlan.Plan ledger(int account) {
        return QaAssetHistoryPlan.build(events(account),FROM,BASE.minusDays(1),startingBalance(account));
    }
}
