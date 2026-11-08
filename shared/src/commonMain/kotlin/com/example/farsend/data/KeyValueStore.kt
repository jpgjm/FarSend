package com.example.farsend.data

/**
 * Settings storage. Backed by SharedPreferences (Android),
 * <FarSend folder>\data\settings.json (desktop), NSUserDefaults (iOS).
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}
