package org.entur.products.partnerapi.usageparameter

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

data class ChargingMoment(
    val id: String,
    val chargingMomentType: String,
)

@Repository
class ChargingMomentDAO(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {
    private val rowMapper =
        RowMapper { rs, _ ->
            ChargingMoment(
                id = rs.getString("id"),
                chargingMomentType = rs.getString("charging_moment_type"),
            )
        }

    fun findByType(chargingMomentType: String): List<ChargingMoment> =
        jdbcTemplate.query(
            """
            SELECT id, charging_moment_type
            FROM charging_moment
            WHERE charging_moment_type = :charging_moment_type
            ORDER BY id
            """.trimIndent(),
            MapSqlParameterSource("charging_moment_type", chargingMomentType),
            rowMapper,
        )

    fun findAll(): List<ChargingMoment> =
        jdbcTemplate.query(
            "SELECT id, charging_moment_type FROM charging_moment ORDER BY id",
            rowMapper,
        )
}
