package io.github.deedg.cineswipe.data.db

import androidx.room.TypeConverter
import io.github.deedg.cineswipe.domain.reco.TmdbTag
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Les listes sont stockées en JSON. Les enums utilisent la conversion native de Room (nom en TEXT).
 * La sérialisation vit dans [DbJson] : Room (KSP) analyse cette classe et ne doit y voir que des
 * types explicites, pas les sérialiseurs générés par le plugin kotlinx.serialization.
 */
class Converters {

    @TypeConverter
    fun castToJson(value: List<CastMember>): String = DbJson.encodeCast(value)

    @TypeConverter
    fun castFromJson(value: String): List<CastMember> = DbJson.decodeCast(value)

    @TypeConverter
    fun tagsToJson(value: List<TmdbTag>): String = DbJson.encodeTags(value)

    @TypeConverter
    fun tagsFromJson(value: String): List<TmdbTag> = DbJson.decodeTags(value)

    @TypeConverter
    fun intsToJson(value: List<Int>): String = DbJson.encodeInts(value)

    @TypeConverter
    fun intsFromJson(value: String): List<Int> = DbJson.decodeInts(value)

    @TypeConverter
    fun stringsToJson(value: List<String>): String = DbJson.encodeStrings(value)

    @TypeConverter
    fun stringsFromJson(value: String): List<String> = DbJson.decodeStrings(value)
}

internal object DbJson {
    private val json: Json = Json { ignoreUnknownKeys = true }
    private val castSerializer: KSerializer<List<CastMember>> = ListSerializer(CastMember.serializer())
    private val tagsSerializer: KSerializer<List<TmdbTag>> = ListSerializer(TmdbTag.serializer())
    private val intsSerializer: KSerializer<List<Int>> = ListSerializer(Int.serializer())
    private val stringsSerializer: KSerializer<List<String>> = ListSerializer(String.serializer())

    fun encodeCast(value: List<CastMember>): String = json.encodeToString(castSerializer, value)

    fun decodeCast(value: String): List<CastMember> =
        runCatching { json.decodeFromString(castSerializer, value) }.getOrDefault(emptyList())

    fun encodeTags(value: List<TmdbTag>): String = json.encodeToString(tagsSerializer, value)

    fun decodeTags(value: String): List<TmdbTag> =
        runCatching { json.decodeFromString(tagsSerializer, value) }.getOrDefault(emptyList())

    fun encodeInts(value: List<Int>): String = json.encodeToString(intsSerializer, value)

    fun decodeInts(value: String): List<Int> =
        runCatching { json.decodeFromString(intsSerializer, value) }.getOrDefault(emptyList())

    fun encodeStrings(value: List<String>): String = json.encodeToString(stringsSerializer, value)

    fun decodeStrings(value: String): List<String> =
        runCatching { json.decodeFromString(stringsSerializer, value) }.getOrDefault(emptyList())
}
