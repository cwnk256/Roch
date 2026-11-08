from pathlib import Path
import zipfile
import tempfile
import re

from docx import Document
from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT, WD_ROW_HEIGHT_RULE
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, Pt


ROOT = Path(r"D:\\idea\\rag-xmut\\artifacts\\test-report")
SOURCE = ROOT / "template.docx"
TARGET = ROOT / "Roch项目测试计划与报告-LoadRunner更新版.docx"


def remove_all_runs(paragraph):
    for run in list(paragraph.runs):
        paragraph._p.remove(run._r)


def set_paragraph(paragraph, text, *, bold=False, size=None, align=None):
    remove_all_runs(paragraph)
    if text:
        run = paragraph.add_run(text)
        run.bold = bold
        if size:
            run.font.size = Pt(size)
    if align is not None:
        paragraph.alignment = align


def clear_cell(cell):
    tc = cell._tc
    for child in list(tc):
        if child.tag != qn("w:tcPr"):
            tc.remove(child)
    p = OxmlElement("w:p")
    tc.append(p)
    return cell.paragraphs[0]


def set_cell(cell, text, *, bold=False, size=9, align=None, blank_lines=0):
    p = clear_cell(cell)
    p.paragraph_format.space_after = Pt(0)
    p.paragraph_format.line_spacing = 1.0
    if align is not None:
        p.alignment = align
    run = p.add_run(text)
    run.bold = bold
    run.font.size = Pt(size)
    if blank_lines:
        p.add_run("\n" * blank_lines)
    cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER


def set_unique_row_cells(row, values):
    seen = set()
    for index, text in values.items():
        if index >= len(row.cells):
            continue
        cell = row.cells[index]
        key = id(cell._tc)
        if key in seen:
            continue
        seen.add(key)
        set_cell(cell, text)


def set_table_font(table, size=9):
    for row in table.rows:
        for cell in row.cells:
            for paragraph in cell.paragraphs:
                for run in paragraph.runs:
                    run.font.size = Pt(size)


def set_row_at_least(row, inches):
    row.height = Inches(inches)
    row.height_rule = WD_ROW_HEIGHT_RULE.AT_LEAST


def remove_drawings_from_container(container):
    for drawing in list(container._element.xpath(".//w:drawing")):
        parent = drawing.getparent()
        parent.remove(drawing)


def strip_unused_media(path):
    temp_path = path.with_suffix(".clean.docx")
    with zipfile.ZipFile(path, "r") as source, zipfile.ZipFile(temp_path, "w", zipfile.ZIP_DEFLATED) as target:
        document_xml = source.read("word/document.xml").decode("utf-8", "ignore")
        rels_xml = source.read("word/_rels/document.xml.rels").decode("utf-8", "ignore")
        used_ids = set(re.findall(r"r:(?:embed|id)=\"([^\"]+)\"", document_xml))
        media_targets = set()
        for rel_id in used_ids:
            match = re.search(r"Id=\"" + re.escape(rel_id) + r"\"[^>]*Target=\"([^\"]+)\"", rels_xml)
            if match and "media/" in match.group(1):
                media_targets.add("word/" + match.group(1).lstrip("/"))
        rels_xml = re.sub(
            r"<Relationship\\s+[^>]*Target=\"(?:media/)[^\"]+\"[^>]*/>",
            "",
            rels_xml,
        )
        for item in source.infolist():
            if item.filename.startswith("word/media/") and item.filename not in media_targets:
                continue
            content = source.read(item.filename)
            if item.filename == "word/_rels/document.xml.rels":
                content = rels_xml.encode("utf-8")
            target.writestr(item, content)
    path.unlink()
    temp_path.rename(path)


def configure_case_table(table, case):
    set_unique_row_cells(table.rows[0], {0: "用例编号", 4: "对应需求", 5: case["requirement_id"]})
    set_unique_row_cells(table.rows[1], {0: "测试内容", 1: case["content"]})
    set_unique_row_cells(table.rows[2], {0: "设计人员", 1: "Roch 项目组", 4: "设计时间：2026-09-19"})
    set_unique_row_cells(table.rows[3], {0: "执行人员", 1: "自动化测试与接口验证", 4: "执行时间：2026-09-19"})
    set_unique_row_cells(table.rows[4], {0: "前置条件", 1: case["precondition"]})
    set_unique_row_cells(table.rows[5], {0: "操作步骤及预期结果", 1: case["steps"]})
    set_unique_row_cells(table.rows[6], {0: "验证步骤及实际结果", 1: case["actual"]})
    set_unique_row_cells(table.rows[7], {0: "结果截图", 1: "结果截图（待插入图片）"})
    set_row_at_least(table.rows[7], 1.8)
    set_unique_row_cells(table.rows[8], {0: "评判标准", 1: "无阻断性缺陷且接口响应、页面展示和持久化结果符合预期则判定为通过；存在待补充的人工截图或外部模型验证时，在结论中单独标注。"})
    set_unique_row_cells(table.rows[9], {0: "缺陷编号", 4: "首次测试结果：" + case["first_result"]})
    set_unique_row_cells(table.rows[10], {0: "是否回归", 1: case["regression"] + "；最终测试结果：" + case["final_result"]})
    set_table_font(table)


CASES = [
    {"requirement_id": "DOC-IMP-01", "content": "支持 TXT、Word、PDF、Excel 文档导入，并完成文本清洗、标题感知和语义切片。", "precondition": "Milvus 已通过 Docker 启动；RAG 服务可访问；准备可读的 TXT、DOCX、PDF、XLSX 测试文件。", "steps": "1. 分别选择四种格式文件。\n2. 调用对应导入接口并提交文件。\n3. 检查返回的文档标识、MD5 与切片数量。\n预期：文件被解析并写入向量库，返回成功结果。", "actual": "已通过后端测试覆盖解析、切片及接口契约；四类格式的导入流程符合预期。\n实际结果：通过。", "first_result": "通过", "regression": "是", "final_result": "通过"},
    {"requirement_id": "DOC-IMP-02", "content": "相同文件按原始字节计算 MD5，重复导入应去重；切片策略变化时支持版本化重建。", "precondition": "已准备同一文件的两次导入请求，并配置有效的切片 pipeline 版本。", "steps": "1. 首次导入文件。\n2. 再次导入完全相同的文件。\n3. 修改切片版本后执行重建索引。\n预期：重复文件被识别；版本变化可触发重建且不产生错误重复数据。", "actual": "MD5 去重与版本化重建相关测试通过；重复导入返回去重结果，重建流程可执行。\n实际结果：通过。", "first_result": "通过", "regression": "是", "final_result": "通过"},
    {"requirement_id": "DOC-MGT-01", "content": "分页查询文档列表，读取文档详情，并分页查看文档切片与章节信息。", "precondition": "向量库中存在至少一个已完成处理的文档。", "steps": "1. 调用文档列表接口。\n2. 根据 documentId 查询详情。\n3. 查询切片并使用 page、size、section 参数分页或过滤。\n预期：返回稳定的文档元数据、切片内容及分页信息。", "actual": "文档列表、详情、切片分页查询接口已通过自动化测试和运行态验证。\n实际结果：通过。", "first_result": "通过", "regression": "是", "final_result": "通过"},
    {"requirement_id": "DOC-SRH-01", "content": "按自然语言进行文档级语义检索，支持 topK、相似度阈值和 documentIds 筛选。", "precondition": "Milvus 已启动并包含可检索的文档切片；检索接口可访问。", "steps": "1. 提交自然语言查询。\n2. 设置 topK 与相似度阈值。\n3. 分别测试全库检索和指定 documentIds 检索。\n预期：结果按文档聚合排序，并返回命中切片与引用元数据。", "actual": "Milvus 检索、文档级聚合、筛选参数和引用字段已通过后端测试覆盖。\n实际结果：通过。", "first_result": "通过", "regression": "是", "final_result": "通过"},
    {"requirement_id": "DOC-MGT-02", "content": "删除文档的全部版本切片，并支持上传原文件后重新建立索引。", "precondition": "目标文档已导入并存在一个或多个版本；保留原始文件用于重建。", "steps": "1. 查询目标文档及其版本。\n2. 调用删除接口。\n3. 重新上传原始文件执行 reindex。\n预期：删除范围覆盖所有版本；重建后产生可查询的新切片。", "actual": "删除与重新索引流程已通过文档服务测试；MD5 校验和错误输入会按接口约定拒绝。\n实际结果：通过。", "first_result": "通过", "regression": "是", "final_result": "通过"},
    {"requirement_id": "CHAT-ONE-01", "content": "单轮问答支持 RAG 开关、召回数量和相似度阈值，并返回回答与引用信息。", "precondition": "RAG 服务和 Milvus 可访问；问答接口可访问；真实模型链路按环境配置。", "steps": "1. 提交单轮问题。\n2. 分别切换 RAG 开关。\n3. 调整 topK 与 threshold。\n预期：请求参数生效；接口返回结构化回答，启用 RAG 时带有检索引用。", "actual": "单轮问答请求模型、检索和响应结构的后端测试已通过；真实 LM Studio 模型全链路需在目标环境补充验证。\n实际结果：自动化验证通过，模型实测待补充。", "first_result": "通过", "regression": "是", "final_result": "通过（模型实测待补充）"},
    {"requirement_id": "CHAT-MULTI-01", "content": "多轮会话支持创建会话、上下文追问、历史消息读取以及 SSE 流式响应。", "precondition": "后端服务运行；会话存储已配置；浏览器或客户端支持 SSE。", "steps": "1. 创建 conversationId。\n2. 提交首轮问题。\n3. 使用同一会话提交追问。\n4. 查询消息历史并观察流式响应。\n预期：上下文关联正确，历史消息可读取，SSE 事件按顺序返回。", "actual": "会话创建、列表和历史消息 API 已在 8082 运行环境验证；多轮上下文及 SSE 契约测试通过。\n实际结果：通过。", "first_result": "通过", "regression": "是", "final_result": "通过"},
    {"requirement_id": "CHAT-MULTI-02", "content": "会话目录支持新建、切换、历史会话列表、当前会话记录展示和清空会话。", "precondition": "Vue 3 前端和后端服务运行；浏览器可访问工作台。", "steps": "1. 打开多轮问答页面。\n2. 新建多个会话并发送消息。\n3. 从固定侧栏切换历史会话。\n4. 清空当前会话。\n预期：侧栏固定不随页面内容滚动，当前会话记录与切换结果一致。", "actual": "前端视觉验证确认固定侧栏、历史会话列表、当前会话记录与会话切换展示正常；接口返回数据可正确映射。\n实际结果：通过。", "first_result": "通过", "regression": "是", "final_result": "通过"},
    {"requirement_id": "TASK-01", "content": "选择一个或多个文档创建 SUMMARY/REPORT 异步任务，查询状态并读取结果。", "precondition": "目标文档状态为 READY；任务持久化和报告输出目录可写；必要字体与模型配置有效。", "steps": "1. 选择文档并提交任务指令。\n2. 轮询任务状态。\n3. 任务完成后查询 result 与 artifacts。\n预期：状态按 PENDING、RUNNING、COMPLETED 或 FAILED 演进，结果可读取，失败信息可定位。", "actual": "任务创建、状态查询、结果持久化和异常处理测试已通过；报告任务线程异常修复后可正常返回任务状态。\n实际结果：通过。", "first_result": "通过", "regression": "是", "final_result": "通过"},
    {"requirement_id": "TASK-02", "content": "导出生成结果为中文 PDF，支持下载，并保留章节内容、来源引用与工件元数据。", "precondition": "已存在可导出的已完成任务；中文字体文件可用；PDF 输出目录可写。", "steps": "1. 查询已完成任务。\n2. 调用 PDF 导出或文件下载接口。\n3. 检查 PDF 文件、中文字体、章节顺序和引用去重。\n预期：文件可下载，中文可读，引用字段不伪造页码。", "actual": "PDF 导出代码、中文字体配置、结果与工件接口已通过后端测试；页码元数据为空时保持为空，不伪造页码。\n实际结果：自动化验证通过，人工 PDF 截图待补充。", "first_result": "通过", "regression": "是", "final_result": "通过（人工截图待补充）"},
    {"requirement_id": "WEB-01", "content": "Vue 3 工作台各页面调用对应后端接口并正确展示：仪表盘、问答、检索、导入、文档管理和总结/报告。", "precondition": "前端依赖已安装；后端 API 可访问；Milvus 通过 Docker 运行。", "steps": "1. 构建 Vue 3 前端。\n2. 依次打开各功能页面。\n3. 执行查询、导入、管理、问答和报告任务操作。\n4. 检查加载、空状态、成功和错误状态。\n预期：接口请求成功后数据正确展示，桌面端侧栏固定且响应式布局不重叠。", "actual": "前端 Node 测试、TypeScript 检查和 Vite 生产构建均通过；运行态接口与页面视觉验证通过。\n实际结果：通过。", "first_result": "通过", "regression": "是", "final_result": "通过"},
]


def main():
    doc = Document(str(SOURCE))
    cover = doc.tables[0]
    for row, value in enumerate(["计算机与信息工程学院", "软件工程（项目组）", "Roch 项目组", "待补充", "待补充"]):
        set_cell(cover.cell(row, 1), value, size=10)
    set_cell(doc.tables[1].cell(0, 0), "老师评语：\n\n（待教师填写）", size=10)

    set_paragraph(doc.paragraphs[2], "\t\t项目测试计划与报告", bold=True, size=20, align=WD_ALIGN_PARAGRAPH.CENTER)
    set_paragraph(doc.paragraphs[15], "测试用例及记录：", bold=True, size=14)
    headings = ["一、文档导入与切片功能测试", "二、重复文档去重与版本化重建测试", "三、文档列表、详情与切片查询测试", "四、文档级语义检索与筛选测试", "五、文档删除与重新索引测试", "六、单轮问答与 RAG 参数测试", "七、多轮会话与上下文问答测试", "八、历史会话目录与会话清空测试", "九、文档总结/报告任务测试", "十、PDF 报告导出测试", "十一、Vue 3 工作台前后端联调测试"]
    heading_paragraphs = [16, 17, 19, 21, 29, 31, 33, 35, 37, 39, 44]
    for index, heading_index in enumerate(heading_paragraphs):
        set_paragraph(doc.paragraphs[heading_index], headings[index], bold=True, size=12)
        configure_case_table(doc.tables[index + 2], CASES[index])

    set_paragraph(doc.paragraphs[47], "二、性能与稳定性测试", bold=True, size=14)
    set_paragraph(doc.paragraphs[48], "本轮完成构建、自动化回归、Docker Milvus 环境接口验证和 LoadRunner 基线负载验证。已使用 LoadRunner VuGen 回放会话目录接口；Controller 的多虚拟用户并发场景尚未完成配置，因此本节不对系统并发容量作出结论。", size=10)
    perf = doc.tables[13]
    set_unique_row_cells(perf.rows[0], {0: "用例编号", 2: "对应需求", 4: "PERF-01"})
    set_unique_row_cells(perf.rows[1], {0: "测试内容", 1: "使用 LoadRunner VuGen 对会话目录接口执行基线负载回放，验证接口在已启动服务和数据库环境中的基本稳定性；并发容量测试待 Controller 场景补充。"})
    set_unique_row_cells(perf.rows[2], {0: "设计人员", 1: "Roch 项目组", 2: "设计时间", 3: "2026-09-19"})
    set_unique_row_cells(perf.rows[3], {0: "执行人员", 1: "Roch 项目组（LoadRunner VuGen）", 2: "执行时间", 3: "2026-09-19 18:46"})
    set_unique_row_cells(perf.rows[4], {0: "前置条件", 1: "Docker 已启动 Milvus；RAG 服务 127.0.0.1:8082 可访问；GET /api/chat/conversations 可返回 HTTP 200。"})
    set_unique_row_cells(perf.rows[5], {0: "脚本配置", 1: "LoadRunner 2023 VuGen，Web - HTTP/HTML 协议；脚本仅包含 GET /api/chat/conversations，并以 GET_conversations 作为事务。"})
    set_unique_row_cells(perf.rows[6], {0: "场景配置", 1: "1 个虚拟用户、1 次迭代的基线回放；开始时间 2026-09-19 18:46:29。Controller 多虚拟用户场景待补充。"})
    set_unique_row_cells(perf.rows[7], {0: "结果截图", 1: "LoadRunner VuGen 回放截图（待插入图片）", 3: "Controller 并发结果截图（待补充）"})
    set_unique_row_cells(perf.rows[8], {0: "结果记录", 1: "Replay status: passed；GET_conversations 事务：Pass；事务耗时 0.138 秒；响应体 668 bytes，响应头 155 bytes；错误数 0。"})
    for row_index in [9, 10, 11]:
        set_unique_row_cells(perf.rows[row_index], {0: "结果截图", 1: "（待插入图片）"})
    set_unique_row_cells(perf.rows[12], {0: "评判标准", 1: "基线回放中事务必须通过且错误数为 0；并发吞吐、P95 响应时间和容量结论仅以实际完成的 Controller 场景统计为准。"})
    set_unique_row_cells(perf.rows[13], {0: "缺陷编号", 2: "首次测试结果", 4: "无缺陷；基线回放通过；多虚拟用户并发测试待补充"})
    set_unique_row_cells(perf.rows[14], {0: "是否回归", 1: "是；最终测试结果：LoadRunner 基线回放通过，未形成并发容量结论"})
    for row_index in [7, 9, 10, 11]:
        set_row_at_least(perf.rows[row_index], 1.4)
    set_table_font(perf)

    set_paragraph(doc.paragraphs[53], "测试结论：", bold=True, size=14)
    set_paragraph(doc.paragraphs[54], "功能性方面，本轮围绕 Roch 基于 RAG 的文档处理与智能问答系统，完成文档导入、切片、去重、版本化重建、文档检索与管理、单轮问答、多轮会话、报告任务、PDF 导出以及 Vue 3 工作台联调测试。后端 Maven 测试、前端 Node 测试、前端生产构建、Docker Milvus 环境下的运行态会话接口验证均通过。", size=10)
    set_paragraph(doc.paragraphs[55], "稳定性与性能方面，本轮完成自动化回归、主要接口验证及 LoadRunner 基线负载回放。会话目录接口在 1 个虚拟用户、1 次迭代下事务通过，耗时 0.138 秒、错误数为 0。尚未形成 Controller 多虚拟用户的吞吐、P95 响应时间和容量结论；LM Studio 真实模型全链路及相应人工截图也需后续补充。", size=10)
    set_paragraph(doc.paragraphs[56], "本报告中的“结果截图”区域均保留为空白占位，便于后续插入实际测试图片。", size=10)
    set_paragraph(doc.paragraphs[57], "测试结果总体判定：已完成范围通过；待补充项目不影响已完成自动化与接口验证结论。", size=10)

    for paragraph in doc.paragraphs:
        remove_drawings_from_container(paragraph)
    for table in doc.tables:
        for row in table.rows:
            for cell in row.cells:
                remove_drawings_from_container(cell)

    doc.core_properties.title = "Roch 项目测试计划与报告"
    doc.core_properties.subject = "基于 RAG 的文档处理与智能问答系统测试计划与报告"
    doc.core_properties.author = "Roch 项目组"
    doc.save(str(TARGET))
    print(TARGET)


if __name__ == "__main__":
    main()
