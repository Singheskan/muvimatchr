package org.example.muvimatchr.session

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

// Persists a List<Int> as a single comma-separated VARCHAR column (session.provider_ids),
// keeping the schema to simple column types consistent with the rest of this table rather
// than reaching for a Postgres array/JSONB type. Stored order is preserved as given; ordering
// is normalised at cache-key construction time by buildDeckCacheKey, not here.
@Converter
class IntListConverter : AttributeConverter<List<Int>, String> {

    override fun convertToDatabaseColumn(attribute: List<Int>?): String =
        attribute?.takeIf { it.isNotEmpty() }?.joinToString(",") ?: ""

    override fun convertToEntityAttribute(dbData: String?): List<Int> =
        dbData?.takeIf { it.isNotBlank() }
            ?.split(",")
            ?.map { it.trim().toInt() }
            ?: emptyList()
}
