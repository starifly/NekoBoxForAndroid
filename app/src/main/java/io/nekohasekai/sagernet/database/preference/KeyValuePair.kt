package io.nekohasekai.sagernet.database.preference

import android.os.Parcel
import android.os.Parcelable
import androidx.room.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

@Entity
class KeyValuePair() : Parcelable {
    companion object {
        const val TYPE_UNINITIALIZED = 0
        const val TYPE_BOOLEAN = 1
        const val TYPE_FLOAT = 2

        @Deprecated("Use TYPE_LONG.")
        const val TYPE_INT = 3
        const val TYPE_LONG = 4
        const val TYPE_STRING = 5
        const val TYPE_STRING_SET = 6

        @JvmField
        val CREATOR = object : Parcelable.Creator<KeyValuePair> {
            override fun createFromParcel(parcel: Parcel): KeyValuePair {
                return KeyValuePair(parcel)
            }

            override fun newArray(size: Int): Array<KeyValuePair?> {
                return arrayOfNulls(size)
            }
        }
    }

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * FROM `KeyValuePair`")
        fun all(): List<KeyValuePair>

        @Query("SELECT * FROM `KeyValuePair` WHERE `key` = :key")
        operator fun get(key: String): KeyValuePair?

        @Insert(onConflict = OnConflictStrategy.REPLACE)
        fun put(value: KeyValuePair): Long

        @Query("DELETE FROM `KeyValuePair` WHERE `key` = :key")
        fun delete(key: String): Int

        @Query("DELETE FROM `KeyValuePair`")
        fun reset(): Int

        @Insert
        fun insert(list: List<KeyValuePair>)
    }

    @PrimaryKey
    var key: String = ""
    var valueType: Int = TYPE_UNINITIALIZED
    var value: ByteArray = ByteArray(0)

    fun validate(): KeyValuePair {
        require(key.isNotBlank()) { "Setting key cannot be blank" }
        when (valueType) {
            TYPE_BOOLEAN -> require(value.isNotEmpty()) { "Setting $key (boolean) payload is empty" }
            TYPE_FLOAT -> require(value.size >= 4) { "Setting $key (float) payload truncated: ${value.size} bytes" }
            @Suppress("DEPRECATION") TYPE_INT -> require(value.size >= 4) { "Setting $key (int) payload truncated: ${value.size} bytes" }
            TYPE_LONG -> require(value.size >= 8) { "Setting $key (long) payload truncated: ${value.size} bytes" }
            TYPE_STRING -> Unit
            TYPE_STRING_SET -> {
                val buffer = ByteBuffer.wrap(value)
                while (buffer.hasRemaining()) {
                    require(buffer.remaining() >= 4) { "Setting $key (stringSet) entry length header truncated" }
                    val len = buffer.int
                    require(len >= 0 && buffer.remaining() >= len) {
                        "Setting $key (stringSet) entry truncated or invalid length: $len, remaining: ${buffer.remaining()}"
                    }
                    buffer.position(buffer.position() + len)
                }
            }
            else -> throw IllegalArgumentException("Setting $key has invalid valueType: $valueType")
        }
        return this
    }

    val boolean: Boolean?
        get() = if (valueType == TYPE_BOOLEAN && value.isNotEmpty()) ByteBuffer.wrap(value).get() != 0.toByte() else null
    val float: Float?
        get() = if (valueType == TYPE_FLOAT && value.size >= 4) ByteBuffer.wrap(value).float else null

    @Suppress("DEPRECATION")
    @Deprecated("Use long.", ReplaceWith("long"))
    val int: Int?
        get() = if (valueType == TYPE_INT && value.size >= 4) ByteBuffer.wrap(value).int else null
    val long: Long?
        get() = when (valueType) {
            @Suppress("DEPRECATION") TYPE_INT -> if (value.size >= 4) ByteBuffer.wrap(value).int.toLong() else null
            TYPE_LONG -> if (value.size >= 8) ByteBuffer.wrap(value).long else null
            else -> null
        }
    val string: String?
        get() = if (valueType == TYPE_STRING) String(value) else null
    val stringSet: Set<String>?
        get() = if (valueType == TYPE_STRING_SET) {
            runCatching {
                val buffer = ByteBuffer.wrap(value)
                val result = HashSet<String>()
                while (buffer.hasRemaining()) {
                    if (buffer.remaining() < 4) return null
                    val len = buffer.int
                    if (len < 0 || buffer.remaining() < len) return null
                    val chArr = ByteArray(len)
                    buffer.get(chArr)
                    result.add(String(chArr))
                }
                result
            }.getOrNull()
        } else null

    @Ignore
    constructor(key: String) : this() {
        this.key = key
    }

    // putting null requires using DataStore
    fun put(value: Boolean): KeyValuePair {
        valueType = TYPE_BOOLEAN
        this.value = ByteBuffer.allocate(1).put((if (value) 1 else 0).toByte()).array()
        return this
    }

    fun put(value: Float): KeyValuePair {
        valueType = TYPE_FLOAT
        this.value = ByteBuffer.allocate(4).putFloat(value).array()
        return this
    }

    @Suppress("DEPRECATION")
    @Deprecated("Use long.")
    fun put(value: Int): KeyValuePair {
        valueType = TYPE_INT
        this.value = ByteBuffer.allocate(4).putInt(value).array()
        return this
    }

    fun put(value: Long): KeyValuePair {
        valueType = TYPE_LONG
        this.value = ByteBuffer.allocate(8).putLong(value).array()
        return this
    }

    fun put(value: String): KeyValuePair {
        valueType = TYPE_STRING
        this.value = value.toByteArray()
        return this
    }

    fun put(value: Set<String>): KeyValuePair {
        valueType = TYPE_STRING_SET
        val stream = ByteArrayOutputStream()
        val intBuffer = ByteBuffer.allocate(4)
        for (v in value) {
            val bytes = v.toByteArray()       // compute UTF-8 bytes once
            intBuffer.rewind()
            stream.write(intBuffer.putInt(bytes.size).array())  // write byte count (not char count)
            stream.write(bytes)
        }
        this.value = stream.toByteArray()
        return this
    }

    @Suppress("IMPLICIT_CAST_TO_ANY")
    override fun toString(): String {
        return when (valueType) {
            TYPE_BOOLEAN -> boolean
            TYPE_FLOAT -> float
            TYPE_LONG -> long
            TYPE_STRING -> string
            TYPE_STRING_SET -> stringSet
            else -> null
        }?.toString() ?: "null"
    }

    constructor(parcel: Parcel) : this() {
        key = parcel.readString()!!
        valueType = parcel.readInt()
        value = parcel.createByteArray()!!
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(key)
        parcel.writeInt(valueType)
        parcel.writeByteArray(value)
    }

    override fun describeContents(): Int {
        return 0
    }

}
