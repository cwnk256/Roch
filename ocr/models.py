"""Pinned PaddleX inference archives and their explicit PP-StructureV3 paths."""

from pathlib import Path


MODEL_ROOT = Path("/opt/ocr-models")
MODEL_ARCHIVE_BASE = (
    "https://paddle-model-ecology.bj.bcebos.com"
    "/paddlex/official_inference_model/paddle3.0.0"
)
MODEL_FILES = {"inference.json", "inference.pdiparams", "inference.yml"}
MODELS = {
    "layout_detection": (
        "PP-DocLayout_plus-L",
        "80fff9c2fb598ca27380a48e733ac47ba022aaf06706ea9c39273ae63d7940dc",
    ),
    "doc_orientation_classify": (
        "PP-LCNet_x1_0_doc_ori",
        "282337df5c41f7cdf8dacd5acf71fddfdc10218399f4b318463c17f4eae96c97",
    ),
    "text_detection": (
        "PP-OCRv5_mobile_det",
        "50446e5d01ac2a73d5319c89513281f6578414c888c602f9af13f93feefffc58",
    ),
    "text_recognition": (
        "PP-OCRv5_mobile_rec",
        "566b9512b34e34a9f0db54d87b51fa5a0b9ed2cf1ab7e49728cc0b8b5a64f414",
    ),
    "table_classification": (
        "PP-LCNet_x1_0_table_cls",
        "d00942b82838dd00b8a04f4db4f5cc0c146d27f4a56eb2fd3986de86e3e844bf",
    ),
    "wired_table_structure_recognition": (
        "SLANeXt_wired",
        "9803e7fb73f895846184ce18208503ca041b57f7d2cac6145bb302c76476f609",
    ),
    "wireless_table_structure_recognition": (
        "SLANet_plus",
        "68526c5c6573a8a17cdc14805e026f11c2545e42d89a1317bb58d3e1e4b58318",
    ),
    "wired_table_cells_detection": (
        "RT-DETR-L_wired_table_cell_det",
        "da4a5ec40f1d86fd00aa6dc739e8e9d18a575b9960c0798be3ec93c47025074d",
    ),
    "wireless_table_cells_detection": (
        "RT-DETR-L_wireless_table_cell_det",
        "6f0a78b0444fb0e50f7762a78c15ca38df6402b814558ef00dbcb1f5fc2a5a3d",
    ),
}


def model_parameters():
    parameters = {}
    for module, (name, _) in MODELS.items():
        directory = MODEL_ROOT / name
        if any(not (directory / filename).is_file() for filename in MODEL_FILES):
            raise RuntimeError("Required OCR model files are absent")
        parameters[f"{module}_model_name"] = name
        parameters[f"{module}_model_dir"] = str(directory)
    return parameters
