package com.sd.lib.singlecache

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/** 默认的Moshi转换器，写入时保留null并校验非法UTF-16，读取时校验非法UTF-8 */
internal object DefaultObjectConverter {
  private val _moshi = Moshi.Builder()
    .add(String::class.java, StrictStringJsonAdapter.nullSafe())
    .add(Char::class.javaPrimitiveType!!, StrictCharJsonAdapter)
    .add(Char::class.javaObjectType, StrictCharJsonAdapter.nullSafe())
    .addLast(KotlinJsonAdapterFactory())
    .build()

  fun <T> encode(value: T, clazz: Class<T>): ByteArray {
    // 写出null，否则可空字段的默认值不是null时，读回来会变成默认值
    return _moshi.adapter(clazz).serializeNulls().toJson(value).toByteArray()
  }

  fun <T> decode(bytes: ByteArray, clazz: Class<T>): T {
    return checkNotNull(
      _moshi.adapter(clazz).fromJson(bytes.decodeToString(throwOnInvalidSequence = true)),
    )
  }
}

private object StrictStringJsonAdapter : JsonAdapter<String>() {
  override fun fromJson(reader: JsonReader): String = reader.nextString()

  override fun toJson(writer: JsonWriter, value: String?) {
    checkNotNull(value)
    try {
      value.encodeToByteArray(throwOnInvalidSequence = true)
    } catch (error: CharacterCodingException) {
      throw JsonDataException("Invalid UTF-16 at path ${writer.path}", error)
    }
    writer.value(value)
  }
}

private object StrictCharJsonAdapter : JsonAdapter<Char>() {
  override fun fromJson(reader: JsonReader): Char {
    val value = reader.nextString()
    if (value.length != 1) {
      throw JsonDataException("Expected a char but was \"$value\" at path ${reader.path}")
    }
    return value[0]
  }

  override fun toJson(writer: JsonWriter, value: Char?) {
    checkNotNull(value)
    if (Character.isSurrogate(value)) {
      throw JsonDataException("Invalid UTF-16 at path ${writer.path}", CharacterCodingException())
    }
    writer.value(value.toString())
  }
}
