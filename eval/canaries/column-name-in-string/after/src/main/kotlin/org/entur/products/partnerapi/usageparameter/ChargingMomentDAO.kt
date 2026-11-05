package org.entur.products.partnerapi.usageparameter

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

data class ChargingMoment(
    val id: String,
    val chargingMoment: String,
)

@Repository
class ChargingMomentDAO(
    private val jdbcTemplate: NamedParameterJdbcTemplate,
) {
    private val rowMapper =
        RowMapper { rs, _ ->
            ChargingMoment(
                id = rs.getString("id"),
                chargingMoment = rs.getString("charging_moment"),
            )
        }

    fun findByType(chargingMoment: String): List<ChargingMoment> =
        jdbcTemplate.query(
            """
            SELECT id, charging_moment
            FROM charging_moment
            WHERE charging_moment = :charging_moment
            ORDER BY id
            """.trimIndent(),
            MapSqlParameterSource("charging_moment", chargingMoment),
            rowMapper,
        )

    fun findAll(): List<ChargingMoment> =
        jdbcTemplate.query(
            "SELECT id, charging_moment FROM charging_moment ORDER BY id",
            rowMapper,
        )
}
