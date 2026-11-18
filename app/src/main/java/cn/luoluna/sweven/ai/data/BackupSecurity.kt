package cn.luoluna.sweven.ai.data

/** 备份是外部输入，不能授予代码执行或网络服务权限。 */
internal object BackupSecurity {
    private val sensitiveSettings = mapOf(
        "scriptToolAutoApprove" to "脚本工具自动批准",
        "script_tool_auto_approve" to "脚本工具自动批准",
        "lanEnabled" to "局域网开关",
        "lan_enabled" to "局域网开关",
        "searchEndpointUserConfirmed" to "内网搜索端点信任",
        "search_endpoint_user_confirmed" to "内网搜索端点信任",
    )

    fun safeSettings(settings: Map<String, String>): Map<String, String> =
        settings.filterKeys { it !in sensitiveSettings }

    fun ignoredSettingLabels(settings: Map<String, String>): List<String> =
        settings.keys.mapNotNull { sensitiveSettings[it] }.distinct()

    /** 不将授权状态从外部文件提升为可信状态；已拉黑记录也需用户重新处理。 */
    fun resetDevices(devices: List<cn.luoluna.sweven.ai.data.db.AuthDeviceEntity>) = devices.map {
        it.copy(
            status = cn.luoluna.sweven.ai.data.db.AuthDeviceEntity.STATUS_PENDING,
            authorizedAt = null,
            tokenHash = "",
        )
    }
}
