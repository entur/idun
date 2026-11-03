package org.entur.products.genericParameterAssignment

import org.slf4j.LoggerFactory

enum class LogicalOperator(
    val value: String,
) {
    AND("and"),
    OR("or"),
    NOT("not"),
}

data class GroupingOperator(
    val operator: LogicalOperator,
    val parameterIds: List<String>,
)

class GroupingOperatorMapper {
    private val log = LoggerFactory.getLogger(GroupingOperatorMapper::class.java)

    fun toGroupingOperator(
        rawOperator: String,
        parameterIds: List<String>,
    ): GroupingOperator {
        val operator = decodeOperator(rawOperator)
        log.debug("Decoded operator {} for {} parameters", operator, parameterIds.size)
        return GroupingOperator(operator, parameterIds)
    }

    private fun decodeOperator(s: String): LogicalOperator = LogicalOperator.valueOf(s)

    fun toRawOperator(operator: LogicalOperator): String = operator.name
}
