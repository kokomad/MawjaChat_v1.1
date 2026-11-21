package com.khader.mawjachat.data

import android.content.Context
import java.util.UUID

/**
 * حفظ أسماء المستخدمين (أسمائي + جهات الاتصال) ومنع تكرار الأسماء.
 */
class UserDirectory(context: Context) {

    private val prefs = context.getSharedPreferences("MawjaUsers", Context.MODE_PRIVATE)

    companion object {
        const val MAX_LEN = 24
        fun normalize(name: String): String = name.trim().replace(Regex("\\s+"), " ")
    }

    /** معرّف ثابت لهذا الجهاز، يستخدمه الخادم لحجز الاسم لجهاز واحد فقط. */
    val deviceId: String
        get() {
            var id = prefs.getString("device_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString("device_id", id).apply()
            }
            return id
        }

    fun myNames(): List<String> = readList("my_names")
    fun contacts(): List<String> = readList("contacts")

    fun saveMyName(name: String) = addUnique("my_names", name)
    fun saveContact(name: String) = addUnique("contacts", name)

    fun forgetMyName(name: String) {
        val n = normalize(name)
        val list = readList("my_names").filterNot { it.equals(n, ignoreCase = true) }
        prefs.edit().putString("my_names", list.joinToString("\n")).apply()
    }

    /** يرجع نص الخطأ أو null إذا كانت الأسماء صالحة. */
    fun validate(sender: String, receiver: String): String? {
        val s = normalize(sender)
        val r = normalize(receiver)
        if (s.isEmpty() || r.isEmpty()) return "يرجى إدخال اسم المرسل واسم المستقبل والكود المشترك"
        if (s.length > MAX_LEN || r.length > MAX_LEN) return "الاسم طويل جداً (الحد الأقصى $MAX_LEN حرفاً)"
        if (s.equals(r, ignoreCase = true)) return "لا يمكن أن يتطابق اسم المرسل مع اسم المستقبل"
        if (myNames().any { it.equals(r, ignoreCase = true) }) return "اسم المستقبل \"$r\" هو أحد أسمائك المسجّلة"
        if (contacts().any { it.equals(s, ignoreCase = true) }) return "الاسم \"$s\" محفوظ كجهة اتصال، اختر اسماً مختلفاً لك"
        return null
    }

    private fun readList(key: String): List<String> =
        (prefs.getString(key, "") ?: "").split("\n").filter { it.isNotBlank() }

    private fun addUnique(key: String, name: String) {
        val n = normalize(name)
        if (n.isEmpty()) return
        val list = readList(key).toMutableList()
        if (list.none { it.equals(n, ignoreCase = true) }) {
            list.add(0, n)
            prefs.edit().putString(key, list.take(30).joinToString("\n")).apply()
        }
    }
}
