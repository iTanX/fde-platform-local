package com.fde.workbench.dao.entity;

/**
 * 流程节点 + 其下输入项 / 输出项 / 技能关联的整条查询行（{@code WorkflowMapper.selectNodeDetails} 的投影）。
 * <p>
 * 为什么用一张 LEFT JOIN 结果再在 Service 里归并，而不是"先查节点、再逐个节点查子表"：
 * 节点数通常很少（3–10 个），一次查询即可拿到全部子项，避免 N+1；同时保持三类子项各自的行可以按
 * 自己的 {@code seq} 排序（同一个节点有多个输出项 / 输入项 / 技能时行数会相乘，归并时按 id 去重）。
 * <p>
 * 各类子项字段在"不是这一类"时为 null（例如某行来自输出项，输入项与技能列都是 null）。
 */
public class WorkflowNodeRow {

    // ---------------- 节点本身 ----------------
    private Long nodeId;
    private Integer nodeSeq;
    private String nodeName;
    private String nodeDescription;
    private String nodeInputType;

    // ---------------- 技能关联（wb_workflow_node_skill JOIN wb_skill）----------------
    private Long skillId;
    private String skillName;
    private Integer skillSeq;

    // ---------------- 固定输入项（wb_node_input_item）----------------
    private Long inputItemId;
    private Integer inputSeq;
    private String inputName;
    private String inputDescription;
    private String inputFileKind;
    private Boolean inputRequired;

    // ---------------- 固定输出项（wb_node_output_item）----------------
    private Long outputItemId;
    private Integer outputSeq;
    private String outputItemKey;
    private String outputName;
    private String outputDescription;
    private String outputFileKind;
    private Boolean outputRequired;

    public Long getNodeId() {
        return nodeId;
    }

    public void setNodeId(Long nodeId) {
        this.nodeId = nodeId;
    }

    public Integer getNodeSeq() {
        return nodeSeq;
    }

    public void setNodeSeq(Integer nodeSeq) {
        this.nodeSeq = nodeSeq;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public String getNodeDescription() {
        return nodeDescription;
    }

    public void setNodeDescription(String nodeDescription) {
        this.nodeDescription = nodeDescription;
    }

    public String getNodeInputType() {
        return nodeInputType;
    }

    public void setNodeInputType(String nodeInputType) {
        this.nodeInputType = nodeInputType;
    }

    public Long getSkillId() {
        return skillId;
    }

    public void setSkillId(Long skillId) {
        this.skillId = skillId;
    }

    public String getSkillName() {
        return skillName;
    }

    public void setSkillName(String skillName) {
        this.skillName = skillName;
    }

    public Integer getSkillSeq() {
        return skillSeq;
    }

    public void setSkillSeq(Integer skillSeq) {
        this.skillSeq = skillSeq;
    }

    public Long getInputItemId() {
        return inputItemId;
    }

    public void setInputItemId(Long inputItemId) {
        this.inputItemId = inputItemId;
    }

    public Integer getInputSeq() {
        return inputSeq;
    }

    public void setInputSeq(Integer inputSeq) {
        this.inputSeq = inputSeq;
    }

    public String getInputName() {
        return inputName;
    }

    public void setInputName(String inputName) {
        this.inputName = inputName;
    }

    public String getInputDescription() {
        return inputDescription;
    }

    public void setInputDescription(String inputDescription) {
        this.inputDescription = inputDescription;
    }

    public String getInputFileKind() {
        return inputFileKind;
    }

    public void setInputFileKind(String inputFileKind) {
        this.inputFileKind = inputFileKind;
    }

    public Boolean getInputRequired() {
        return inputRequired;
    }

    public void setInputRequired(Boolean inputRequired) {
        this.inputRequired = inputRequired;
    }

    public Long getOutputItemId() {
        return outputItemId;
    }

    public void setOutputItemId(Long outputItemId) {
        this.outputItemId = outputItemId;
    }

    public Integer getOutputSeq() {
        return outputSeq;
    }

    public void setOutputSeq(Integer outputSeq) {
        this.outputSeq = outputSeq;
    }

    public String getOutputItemKey() {
        return outputItemKey;
    }

    public void setOutputItemKey(String outputItemKey) {
        this.outputItemKey = outputItemKey;
    }

    public String getOutputName() {
        return outputName;
    }

    public void setOutputName(String outputName) {
        this.outputName = outputName;
    }

    public String getOutputDescription() {
        return outputDescription;
    }

    public void setOutputDescription(String outputDescription) {
        this.outputDescription = outputDescription;
    }

    public String getOutputFileKind() {
        return outputFileKind;
    }

    public void setOutputFileKind(String outputFileKind) {
        this.outputFileKind = outputFileKind;
    }

    public Boolean getOutputRequired() {
        return outputRequired;
    }

    public void setOutputRequired(Boolean outputRequired) {
        this.outputRequired = outputRequired;
    }
}
