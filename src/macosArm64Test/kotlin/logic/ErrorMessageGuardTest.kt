package logic

import contract.testing.ErrorMessageGuard
import kotlin.test.Test

/** pulse 的业务错误 message 必须是 [PulseErrorCodes] 里的机器码（spec docs/pulse-errors.md）。 */
class ErrorMessageGuardTest {
    @Test
    fun businessErrorMessagesAreMachineCodes() {
        ErrorMessageGuard.assertModuleClean(
            allowlist = setOf(
                // 统一出口：code 形参只接收 PulseErrorCodes 常量；调用点由下面的 constructors 逐个检查。
                "kotlin/logic/PulseErrorCodes.kt#BadRequestException#code",
                "kotlin/logic/PulseErrorCodes.kt#ValidationError.of#code",
                "kotlin/logic/PulseErrorCodes.kt#NotFoundException#code",
                "kotlin/logic/PulseErrorCodes.kt#HttpException#code",
                // HTTP 接入：rejection.code 来自 PulseAppLogic.ingestRejection，只取 PulseErrorCodes.INGEST_* 常量
                // （IngestRejection 的构造点由下面的 constructors 检查）。
                "kotlin/controller/open/pulse/PulseIngestHttpController.kt#pulseInvalid#rejection.code",
            ),
            constructors = ErrorMessageGuard.DEFAULT_CONSTRUCTORS + mapOf(
                "pulseInvalid" to listOf(0),
                "pulseNotFound" to listOf(0),
                "pulseTaken" to listOf(0),
                "IngestRejection" to listOf(0),
            ),
        )
    }
}
