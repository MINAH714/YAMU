package com.yamu.be.admin;

import com.yamu.be.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 운영 API. 인증은 Bearer가 아니라 {@code X-Admin-Key}다({@link AdminKeyInterceptor}). */
@Tag(name = "운영")
@RestController
public class JobRunController {

    private final JobRunService service;

    public JobRunController(JobRunService service) {
        this.service = service;
    }

    @Operation(summary = "정기 작업 수동 실행",
            description = "청구 확정(bill-confirm) 등 정기 작업을 기준 시각(asOf)으로 즉시 실행한다. 이미 처리된 대상은 다시 처리하지 않는다. "
                    + "asOf를 미래로 두면 그 날짜 기준으로 처리하고 되돌리지 않는다(이용기간이 안 끝난 청구서도 확정되어, 실제 마감일까지 "
                    + "그 카드·그 청구 월 신용 결제는 422 BILL_CLOSED). "
                    + "403 ADMIN_ONLY(키 불일치), 404 JOB_NOT_FOUND, 409 JOB_ALREADY_RUNNING",
            parameters = @Parameter(name = AdminKeyInterceptor.HEADER, in = ParameterIn.HEADER, required = true))
    @PostMapping("/api/admin/jobs/{jobName}/run")
    public ApiResponse<JobRunResponse> run(@PathVariable String jobName,
            @RequestBody(required = false) JobRunRequest request) {
        return ApiResponse.ok(service.run(jobName, request));
    }
}
