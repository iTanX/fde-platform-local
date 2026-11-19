package com.fde.workbench.service;

import com.fde.workbench.common.error.BizException;
import com.fde.workbench.common.error.ErrorCode;
import com.fde.workbench.dao.entity.SkillReference;
import com.fde.workbench.service.vo.SkillReferencesVO;

import java.util.List;

/**
 * 技能被活跃流程模板或未完成需求引用时的异常（409/10403）。
 * <p>
 * 为什么单独建一个异常类而不是直接抛带文案的 {@link BizException}：
 * api.md §5 要求 10403 的 {@code data} 返回引用清单 {@code {references: [...]}}，
 * 而普通 BizException 的 {@code data} 恒为 null。这里把清单挂在异常上，
 * 由全局异常处理把它写进 {@code data}，从而在不改动其他错误码行为的前提下满足契约。
 * 它同时是 BizException 的子类，未单独处理的场景仍按 409/10403 走。
 */
public class SkillReferencedException extends BizException {

    private static final long serialVersionUID = 1L;

    /** 流程模板节点及需求快照的引用清单，直接作为响应的 data */
    private final transient SkillReferencesVO references;

    private SkillReferencedException(SkillReferencesVO references) {
        super(ErrorCode.REFERENCED, "技能仍被活跃流程或未完成需求引用，无法删除");
        this.references = references;
    }

    /**
     * 由查询结果构造。
     *
     * @param references 引用清单（非空）
     * @return 异常
     */
    public static SkillReferencedException of(List<SkillReference> references) {
        return new SkillReferencedException(SkillReferencesVO.of(references));
    }

    /** 引用清单 */
    public SkillReferencesVO getReferences() {
        return references;
    }
}
