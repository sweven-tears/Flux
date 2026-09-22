package cn.luoluna.sweven.ai.tools.local

import cn.luoluna.sweven.ai.tools.FluxTool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * 时间信息工具：返回设备当前本地时间（年月日/星期/时区/时间戳）。
 * 纯本地读取，无权限、无副作用，作为默认开启的基础工具。
 */
internal fun buildTimeInfoTool(): FluxTool = FluxTool(
    name = "get_time_info",
    displayName = "时间信息",
    description = "获取设备当前的本地日期与时间（年月日、星期、时区、时间戳），无参数",
    parameters = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject { })
    },
    defaultEnabled = true,
) {
    val now = ZonedDateTime.now()
    val date = now.toLocalDate()
    val time = now.toLocalTime().withNano(0)
    val weekday = now.dayOfWeek
    buildJsonObject {
        put("year", date.year)
        put("month", date.monthValue)
        put("day", date.dayOfMonth)
        put("weekday", weekday.getDisplayName(TextStyle.FULL, Locale.getDefault()))
        put("weekday_en", weekday.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
        put("date", date.toString())
        put("time", time.toString())
        put("datetime", now.withNano(0).toString())
        put("timezone", now.zone.id)
        put("utc_offset", now.offset.id)
        put("timestamp_ms", now.toInstant().toEpochMilli())
    }.toString()
}
