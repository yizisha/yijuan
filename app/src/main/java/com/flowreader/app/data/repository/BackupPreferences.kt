package com.flowreader.app.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** 只备份已知的用户偏好；本机授权、应用锁和自动备份目录不随书库迁移。 */
internal object BackupPreferences {
    private val types = mapOf(
        "theme" to "string",
        "speech_enabled" to "boolean",
        "app_visual_style" to "string",
        "color_source" to "string",
        "app_language" to "string",
        "font_size" to "int",
        "line_spacing" to "float",
        "paragraph_spacing" to "float",
        "first_line_indent" to "boolean",
        "reader_margin_dp" to "int",
        "reader_line_chars" to "int",
        "reader_text_align" to "string",
        "reader_chinese_conversion" to "string",
        "reader_color_temperature" to "int",
        "font_family" to "string",
        "reader_theme" to "string",
        "reader_night_palette" to "string",
        "page_mode" to "string",
        "page_turn_animation" to "string",
        "keep_screen_on" to "boolean",
        "screen_timeout_minutes" to "int",
        "eye_protection_interval_minutes" to "int",
        "auto_night_mode" to "boolean",
        "tap_zone_ratio" to "float",
        "reading_reminder_enabled" to "boolean",
        "reading_reminder_hour" to "int",
        "reading_reminder_minute" to "int",
        "search_history" to "string",
        "daily_reading_goal_minutes" to "int",
        "weekly_reading_goal_minutes" to "int",
        "monthly_reading_goal_minutes" to "int",
        "gesture_left_tap" to "string",
        "gesture_middle_tap" to "string",
        "gesture_right_tap" to "string",
        "gesture_swipe_left" to "string",
        "gesture_swipe_right" to "string",
        "gesture_double_tap" to "string",
        "gesture_double_tap_enabled" to "boolean",
        "gesture_tap_zone_mode" to "string",
        "gesture_long_press" to "string",
        "gesture_edge_enabled" to "boolean",
        "gesture_left_edge_width" to "int",
        "gesture_right_edge_width" to "int",
        "custom_font_path" to "string",
        "library_view_mode" to "string",
        "reader_custom_text_color" to "long",
        "reader_custom_background_color" to "long",
        "reader_background_path" to "string",
        "reader_background_scrim" to "float",
        "annotation_display_style" to "string",
        "volume_key_turn_page" to "boolean",
        "hide_grouped_from_all" to "boolean",
        "library_grid_columns" to "int",
        "library_cover_size" to "string",
        "open_read_book_directly" to "boolean",
        "txt_strip_blank_lines" to "boolean",
        "txt_remove_ad_lines" to "boolean",
        "txt_custom_replacements" to "string",
        "txt_chapter_regex" to "string",
        "txt_regex_only" to "boolean"
    )

    suspend fun snapshot(context: Context): JSONObject {
        val result = JSONObject()
        context.dataStore.data.first().asMap().forEach { (key, value) ->
            val type = types[key.name] ?: return@forEach
            result.put(key.name, JSONObject().put("type", type).put("value", value))
        }
        return result
    }

    fun validate(json: JSONObject) {
        json.keys().forEach { name ->
            val expected = types[name] ?: error("备份含未知或本机专用设置：$name")
            val item = json.getJSONObject(name)
            require(item.getString("type") == expected) { "设置类型不匹配：$name" }
            val value = item.get("value")
            require(
                when (expected) {
                    "boolean" -> value is Boolean
                    "string" -> value is String && value.length <= 100_000
                    "int" -> (value is Int || value is Long) && (value as Number).toLong() in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                    "long" -> value is Int || value is Long
                    "float" -> value is Number && value.toFloat().isFinite()
                    else -> false
                }
            ) { "无效设置值：$name" }
        }
    }

    suspend fun apply(context: Context, json: JSONObject, rewrite: (String) -> String = { it }) {
        validate(json)
        context.dataStore.edit { prefs ->
            // 恢复的是一次完整偏好快照，缺失值恢复默认；保留全部本机专用字段。
            prefs.asMap().keys.filter { it.name in types }.forEach { prefs.remove(it) }
            json.keys().forEach { name ->
                val value = json.getJSONObject(name)
                when (types.getValue(name)) {
                    "boolean" -> prefs[booleanPreferencesKey(name)] = value.getBoolean("value")
                    "int" -> prefs[intPreferencesKey(name)] = value.getInt("value")
                    "long" -> prefs[longPreferencesKey(name)] = value.getLong("value")
                    "float" -> prefs[floatPreferencesKey(name)] = value.getDouble("value").toFloat()
                    "string" -> prefs[stringPreferencesKey(name)] = rewrite(value.getString("value"))
                }
            }
        }
    }
}
