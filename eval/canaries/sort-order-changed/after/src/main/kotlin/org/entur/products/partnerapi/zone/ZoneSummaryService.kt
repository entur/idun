package org.entur.products.partnerapi.zone

import org.springframework.stereotype.Service

data class Zone(
    val id: String,
    val name: String,
    val active: Boolean,
)

data class ZoneSummary(
    val id: String,
    val name: String,
)

interface ZoneRepository {
    fun findAll(): List<Zone>
}

@Service
class ZoneSummaryService(
    private val zoneRepository: ZoneRepository,
) {
    fun activeZones(): List<ZoneSummary> =
        zoneRepository
            .findAll()
            .filter { it.active }
            .inDisplayOrder()
            .map { ZoneSummary(it.id, it.name) }

    fun allZones(): List<ZoneSummary> =
        zoneRepository
            .findAll()
            .inDisplayOrder()
            .map { ZoneSummary(it.id, it.name) }

    private fun List<Zone>.inDisplayOrder(): List<Zone> = sortedBy { it.id }
}
