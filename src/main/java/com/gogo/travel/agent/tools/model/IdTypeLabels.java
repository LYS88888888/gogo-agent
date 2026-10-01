package com.gogo.travel.agent.tools.model;

import java.util.Map;

/**
 * 证件类型代码 → 中文名字典（与 flight-manager 数据字典一致）。
 * 供用户信息查询/更新工具共用：查询侧用于回显 label，更新侧用于 id_type 合法性校验。
 *
 */
public final class IdTypeLabels {

    public static final Map<Integer, String> LABELS = Map.ofEntries(
            Map.entry(0, "身份证"),
            Map.entry(1, "护照"),
            Map.entry(2, "其他"),
            Map.entry(3, "回乡证"),
            Map.entry(4, "军官证"),
            Map.entry(5, "警官证"),
            Map.entry(6, "港澳通行证"),
            Map.entry(7, "台胞证"),
            Map.entry(8, "台湾通行证"),
            Map.entry(9, "外国人永久居留身份证")
    );

    private IdTypeLabels() {
    }
}
