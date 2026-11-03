package org.entur.products.partnerapi.zone

class ZoneReference(
    val zoneId: String,
    val version: String,
)

class ZoneReferenceCollector {
    fun distinctReferences(assignments: List<ZoneAssignment>): Set<ZoneReference> {
        val references = mutableSetOf<ZoneReference>()
        for (assignment in assignments) {
            references.add(ZoneReference(assignment.zoneId, assignment.version))
        }
        return references
    }

    fun countDistinct(assignments: List<ZoneAssignment>): Int = distinctReferences(assignments).size
}

data class ZoneAssignment(
    val id: String,
    val zoneId: String,
    val version: String,
)
