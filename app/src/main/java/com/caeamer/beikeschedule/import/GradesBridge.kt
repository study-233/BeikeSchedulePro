package com.caeamer.beikeschedule.import

/**
 * 成绩抓取脚本（assets/import/jw_grades.js）与 Kotlin 的桥接。
 * 每个任务使用独立的桥与请求标识，仅填充本任务对应的结果槽位。
 */
class GradesBridge(
    private val onResult: (
        gpaJson: String,
        gradesJson: String,
        userJson: String,
        xsxxJson: String,
        semJson: String,
        examsJson: String,
        xflbyqJson: String,
        bxkqkJson: String,
    ) -> Unit,
    private val onFailure: (String) -> Unit,
) : JwBridge {

    override fun onError(message: String) = onFailure(message)

    override fun onMessage(fn: String, args: List<String>) {
        if (fn != FN_ON_GRADES_RESULT) return
        onResult(
            args.getOrElse(0) { "" },
            args.getOrElse(1) { "" },
            args.getOrElse(2) { "" },
            args.getOrElse(3) { "" },
            args.getOrElse(4) { "" },
            args.getOrElse(5) { "" },
            args.getOrElse(6) { "" },
            args.getOrElse(7) { "" },
        )
    }

    private companion object {
        const val FN_ON_GRADES_RESULT = "onGradesResult"
    }
}
