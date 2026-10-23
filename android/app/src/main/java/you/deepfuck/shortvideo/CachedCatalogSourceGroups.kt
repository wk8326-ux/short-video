package you.deepfuck.shortvideo

import org.json.JSONObject

internal data class CachedSourceGroup(
    val id: String,
    val name: String,
    val provider: String,
    val libraryIds: List<String>,
    val total: Int,
)

internal fun cachedSourceGroups(
    sourcePayloads: List<JSONObject>,
    pageTotals: Map<String, Int>,
): List<CachedSourceGroup> {
    val sourcesById = sourcePayloads
        .mapNotNull { source ->
            source.optString("id").trim().takeIf(String::isNotBlank)?.let { it to source }
        }
        .toMap()
    val childParents = sourcesById.values.mapNotNull { source ->
        source.optString("parentSourceId").trim().takeIf(String::isNotBlank)
    }.toSet()
    val grouped = linkedMapOf<String, MutableList<JSONObject>>()

    pageTotals.keys.sorted().forEach { sourceId ->
        val source = sourcesById[sourceId] ?: return@forEach
        val provider = source.optString("provider").trim().lowercase().ifBlank { "unknown" }
        val parentId = source.optString("parentSourceId").trim()
        if (parentId.isBlank() && provider in setOf("tvbox", "html") && sourceId in childParents) {
            return@forEach
        }
        val groupId = source.optString("sourceGroupId").trim().ifBlank {
            parentId.takeIf(String::isNotBlank)?.let { "parent:$it" } ?: "source:$sourceId"
        }
        grouped.getOrPut(groupId) { mutableListOf() }.add(source)
    }

    return grouped.map { (groupId, members) ->
        val first = members.first()
        val provider = first.optString("provider").trim().lowercase().ifBlank { "unknown" }
        CachedSourceGroup(
            id = groupId,
            name = first.optString("sourceGroupName").trim()
                .ifBlank { first.optString("name").trim() }
                .ifBlank { "媒体源" },
            provider = provider,
            libraryIds = members.map { it.optString("id").trim() },
            total = members.sumOf { source -> pageTotals[source.optString("id").trim()] ?: 0 },
        )
    }
}
