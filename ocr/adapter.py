"""Translate one PP-StructureV3 page to Roch's geometry-first OCR response."""

from collections import defaultdict
from collections.abc import Mapping
from dataclasses import dataclass
from html.parser import HTMLParser
from math import isfinite
from numbers import Real
from statistics import median


ENGINE_VERSION = "paddleocr-3.5.0-paddlex-3.5.0-paddlepaddle-3.2.2-roch.1"
MAX_TABLES = 64
MAX_CELLS = 1000
MAX_GRID_AXIS = 128
MAX_GRID_SLOTS = 10000
MAX_LINES = 6000
MAX_LINE_TEXT = 8192
MAX_HTML = 512 * 1024


@dataclass(frozen=True)
class GridCell:
    row: int
    column: int
    row_span: int
    column_span: int


class TableShape(HTMLParser):
    """Read only the model's bounded structural grammar. OCR text comes from boxes."""

    PARENTS = {
        "html": {None}, "body": {"html", None}, "table": {"body", None},
        "thead": {"table"}, "tbody": {"table"}, "tfoot": {"table"},
        "tr": {"table", "thead", "tbody", "tfoot"}, "td": {"tr"}, "th": {"tr"},
    }
    INLINE_TAGS = {"b", "strong", "i", "em", "u", "s", "sup", "sub", "span", "p"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []
        self.rows = []
        self.table_seen = False
        self.cell_count = 0
        self.in_cell = False

    def handle_starttag(self, tag, attrs):
        parent = self.stack[-1] if self.stack else None
        if tag == "br" and self.in_cell and not attrs:
            return
        if tag in self.INLINE_TAGS and self.in_cell and not attrs:
            if len(self.stack) >= 16:
                raise ValueError("Table markup nesting exceeds limit")
        elif parent not in self.PARENTS.get(tag, ()):
            raise ValueError("Unexpected table markup")
        elif tag in {"td", "th"}:
            spans = dict(attrs)
            if len(spans) != len(attrs) or spans.keys() - {"rowspan", "colspan"}:
                raise ValueError("Unexpected cell attributes")
            row_span, column_span = (self.span(spans.get(axis, "1")) for axis in ("rowspan", "colspan"))
            self.rows[-1].append((row_span, column_span))
            self.cell_count += 1
            self.in_cell = True
            if self.cell_count > MAX_CELLS:
                raise ValueError("Too many table cells")
        elif attrs:
            raise ValueError("Unexpected table attributes")
        elif tag == "tr":
            if len(self.rows) == MAX_GRID_AXIS:
                raise ValueError("Too many table rows")
            self.rows.append([])
        elif tag == "table":
            if self.table_seen:
                raise ValueError("Expected one table per region")
            self.table_seen = True
        self.stack.append(tag)

    def handle_endtag(self, tag):
        if not self.stack or self.stack.pop() != tag:
            raise ValueError("Unbalanced table markup")
        if tag in {"td", "th"}:
            self.in_cell = False

    def handle_startendtag(self, tag, attrs):
        if tag != "br" or not self.in_cell or attrs:
            raise ValueError("Unexpected table markup")

    def handle_data(self, data):
        if not self.in_cell and data.strip():
            raise ValueError("Unexpected text outside a table cell")

    @staticmethod
    def span(value):
        if not value or len(value) > 3 or not value.isascii() or not value.isdecimal():
            raise ValueError("Invalid table span")
        count = int(value)
        if not 1 <= count <= MAX_GRID_AXIS:
            raise ValueError("Invalid table span")
        return count

    def grid(self):
        if self.stack or not self.table_seen or not self.rows or not self.cell_count:
            raise ValueError("Incomplete table shape")
        cells = []
        occupied = set()
        for row, definitions in enumerate(self.rows):
            column = 0
            for row_span, column_span in definitions:
                while (row, column) in occupied:
                    column += 1
                if row + row_span > len(self.rows) or column + column_span > MAX_GRID_AXIS:
                    raise ValueError("Table cell out of range")
                slots = {
                    (covered_row, covered_column)
                    for covered_row in range(row, row + row_span)
                    for covered_column in range(column, column + column_span)
                }
                if occupied & slots:
                    raise ValueError("Overlapping table spans")
                occupied.update(slots)
                if len(occupied) > MAX_GRID_SLOTS:
                    raise ValueError("Table grid exceeds limit")
                cells.append(GridCell(row, column, row_span, column_span))
                column += column_span
        columns = max(column for _, column in occupied) + 1
        verify_grid(cells, len(self.rows), columns)
        return cells


def verify_grid(cells, rows, columns):
    occupied = set()
    for cell in cells:
        slots = {
            (row, column)
            for row in range(cell.row, cell.row + cell.row_span)
            for column in range(cell.column, cell.column + cell.column_span)
        }
        if occupied & slots:
            raise ValueError("Overlapping table spans")
        occupied.update(slots)
        if len(occupied) > MAX_GRID_SLOTS:
            raise ValueError("Table grid exceeds limit")
    if len(occupied) != rows * columns:
        raise ValueError("Table grid contains gaps")


def sequence(value):
    if hasattr(value, "tolist"):
        value = value.tolist()
    if not isinstance(value, (list, tuple)):
        raise ValueError("Expected a model result array")
    return value


def number(value):
    if isinstance(value, bool) or not isinstance(value, Real) or not isfinite(value):
        raise ValueError("Expected a finite model value")
    return float(value)


def bounds(value):
    points = sequence(value)
    if len(points) == 4 and all(isinstance(point, Real) for point in points):
        x0, y0, x1, y1 = map(number, points)
    else:
        if len(points) == 8:
            coordinates = list(map(number, points))
            xs, ys = coordinates[::2], coordinates[1::2]
        elif len(points) == 4:
            pairs = [sequence(point) for point in points]
            if any(len(point) != 2 for point in pairs):
                raise ValueError("Invalid model polygon")
            xs, ys = zip(*(list(map(number, point)) for point in pairs))
        else:
            raise ValueError("Invalid model rectangle")
        x0, y0, x1, y1 = min(xs), min(ys), max(xs), max(ys)
    if x0 >= x1 or y0 >= y1:
        raise ValueError("Empty model rectangle")
    return x0, y0, x1, y1


def intersection(first, second):
    return max(first[0], second[0]), max(first[1], second[1]), min(first[2], second[2]), min(first[3], second[3])


def area(box):
    return max(0, box[2] - box[0]) * max(0, box[3] - box[1])


def normalize(box, width, height):
    return [coord / limit for coord, limit in zip(box, (width, height, width, height), strict=True)]


def recognize_lines(result, page_box):
    if not isinstance(result, Mapping):
        raise ValueError("Invalid OCR results")
    texts = sequence(result.get("rec_texts"))
    raw_boxes = sequence(result.get("rec_boxes"))
    scores = sequence(result.get("rec_scores"))
    if not len(texts) == len(raw_boxes) == len(scores) or len(texts) > MAX_LINES:
        raise ValueError("Mismatched OCR results")
    lines = []
    for text, raw_box, raw_score in zip(texts, raw_boxes, scores, strict=True):
        if not isinstance(text, str) or len(text) > MAX_LINE_TEXT:
            raise ValueError("Invalid OCR text")
        try:
            score = number(raw_score)
            box = bounds(raw_box)
        except (TypeError, ValueError):
            continue
        clipped = intersection(box, page_box)
        if text.strip() and 0 <= score <= 1 and area(clipped) >= 0.8 * area(box):
            lines.append((text.strip(), clipped, score))
    return lines


def table_regions(result, page_box):
    if not isinstance(result, Mapping):
        raise ValueError("Invalid layout results")
    for entry in sequence(result.get("boxes")):
        if not isinstance(entry, Mapping) or entry.get("label") != "table":
            continue
        try:
            box = bounds(entry.get("coordinate"))
        except (TypeError, ValueError):
            continue
        clipped = intersection(box, page_box)
        if area(clipped) >= 0.9 * area(box):
            yield clipped


def verify_axis(positions, boxes, axis):
    # Adjacent boxes share logical grid boundaries even when separated by a narrow gutter.
    endpoints = defaultdict(list)
    for (start, span), box in zip(positions, boxes, strict=True):
        size = box[axis + 2] - box[axis]
        endpoints[start].append((box[axis], size))
        endpoints[start + span].append((box[axis + 2], size))
    boundaries = {index: median(value for value, _ in samples) for index, samples in endpoints.items()}
    ordered = [boundaries[index] for index in sorted(boundaries)]
    if any(first >= second for first, second in zip(ordered, ordered[1:])):
        raise ValueError("Table geometry conflicts with grid order")
    for index, samples in endpoints.items():
        lower = boundaries.get(index - 1)
        upper = boundaries.get(index + 1)
        nearby_bands = [abs(boundaries[index] - edge) for edge in (lower, upper) if edge is not None]
        scale = min(nearby_bands, default=median(size for _, size in samples))
        tolerance = max(3, 0.34 * scale)
        if any(abs(value - boundaries[index]) > tolerance for value, _ in samples):
            raise ValueError("Table geometry conflicts with cell spans")


def align_cells(cells, boxes):
    # Paddle returns different orders for wired and wireless models. Use image geometry
    # to place boxes, and require the resulting coordinates to corroborate the spans.
    by_row = defaultdict(list)
    for cell in cells:
        by_row[cell.row].append(cell)
    remaining = sorted(boxes, key=lambda box: (box[1], box[0]))
    aligned = []
    start = 0
    for row_cells in by_row.values():
        end = start + len(row_cells)
        group = sorted(remaining[start:end], key=lambda box: box[0])
        if any(first[2] - second[0] > min(first[2] - first[0], second[2] - second[0]) * 0.2
               for first, second in zip(group, group[1:])):
            raise ValueError("Neighboring table cell boxes overlap")
        aligned.extend(group)
        start = end
    verify_axis([(cell.row, cell.row_span) for cell in cells], aligned, 1)
    verify_axis([(cell.column, cell.column_span) for cell in cells], aligned, 0)
    return aligned


def detect_boundaries(boxes, axis):
    lengths = [box[axis + 2] - box[axis] for box in boxes]
    tolerance = max(2.0, min(16.0, median(lengths) * 0.08))
    edges = sorted({box[index] for box in boxes for index in (axis, axis + 2)})
    clusters = [[edges[0]]]
    for edge in edges[1:]:
        if edge - clusters[-1][0] <= tolerance:
            clusters[-1].append(edge)
        else:
            clusters.append([edge])
    if not 3 <= len(clusters) <= MAX_GRID_AXIS + 1:
        raise ValueError("Detected cells do not establish a table grid")
    if any(second[0] - first[-1] <= tolerance for first, second in zip(clusters, clusters[1:])):
        raise ValueError("Table border positions are ambiguous")
    return {edge: index for index, cluster in enumerate(clusters) for edge in cluster}, len(clusters) - 1


def grid_from_detected_boxes(boxes, region):
    # A ruled-table detector can establish a merged cell even if HTML predicts
    # a different span. Require coherent borders, full tiling and region coverage.
    if not 2 <= len(boxes) <= MAX_CELLS:
        raise ValueError("Invalid detected table cell count")
    extent = (
        min(box[0] for box in boxes), min(box[1] for box in boxes),
        max(box[2] for box in boxes), max(box[3] for box in boxes),
    )
    if area(extent) < 0.75 * area(region):
        raise ValueError("Detected table geometry does not cover its region")
    column_edges, columns = detect_boundaries(boxes, 0)
    row_edges, rows = detect_boundaries(boxes, 1)
    pairs = []
    for box in boxes:
        column, row, end_column, end_row = (
            column_edges[box[0]], row_edges[box[1]], column_edges[box[2]], row_edges[box[3]],
        )
        if row >= end_row or column >= end_column:
            raise ValueError("Detected cell is smaller than the border tolerance")
        pairs.append((GridCell(row, column, end_row - row, end_column - column), box))
    pairs.sort(key=lambda pair: (pair[0].row, pair[0].column))
    cells, aligned = zip(*pairs, strict=True)
    verify_grid(cells, rows, columns)
    return cells, aligned


def assign_text(boxes, lines):
    matches = [[] for _ in boxes]
    for text, line_box, score in lines:
        line_area = area(line_box)
        candidates = [
            (area(intersection(line_box, box)) / line_area, index)
            for index, box in enumerate(boxes)
        ]
        qualifying = [index for coverage, index in candidates if coverage >= 0.75]
        if len(qualifying) == 1:
            matches[qualifying[0]].append((text, line_box, score))
    return matches


def adapt_table(result, regions, page_box, page_lines):
    if not isinstance(result, Mapping):
        raise ValueError("Invalid table result")
    markup = result.get("pred_html")
    if not isinstance(markup, str) or len(markup) > MAX_HTML:
        raise ValueError("Invalid table structure")
    shape = TableShape()
    shape.feed(markup)
    shape.close()
    cells = shape.grid()

    model_boxes = sequence(result.get("cell_box_list"))
    if len(model_boxes) > MAX_CELLS:
        raise ValueError("Too many detected cell boxes")
    boxes = [bounds(box) for box in model_boxes]
    matching_regions = [
        region for region in regions
        if all(area(intersection(box, region)) >= 0.9 * area(box) for box in boxes)
    ]
    if len(matching_regions) != 1:
        raise ValueError("Table boxes do not match one source region")
    region = matching_regions[0]
    clipped_boxes = [intersection(box, region) for box in boxes]
    try:
        if len(cells) != len(clipped_boxes):
            raise ValueError("Table structure and box count differ")
        aligned = align_cells(cells, clipped_boxes)
    except ValueError:
        cells, aligned = grid_from_detected_boxes(clipped_boxes, region)
    try:
        table_lines = recognize_lines(result.get("table_ocr_pred"), page_box)
    except ValueError:
        # Some Paddle table branches split OCR boxes and leave the score vector unchanged.
        table_lines = []
    if not table_lines:
        table_lines = [line for line in page_lines if area(intersection(line[1], region)) > 0]
    matches = assign_text(aligned, table_lines)
    width, height = page_box[2:]
    return {
        "box": normalize(region, width, height),
        "cells": [
            {
                "row": cell.row,
                "column": cell.column,
                "rowSpan": cell.row_span,
                "columnSpan": cell.column_span,
                "text": " ".join(text for text, _, _ in sorted(lines, key=lambda line: (line[1][1], line[1][0]))),
                "box": normalize(box, width, height),
                "confidence": min((score for _, _, score in lines), default=0.0),
            }
            for cell, box, lines in zip(cells, aligned, matches, strict=True)
        ],
    }


def input_orientation(box, angle):
    # Paddle applies a counterclockwise rotation before layout and OCR.
    left, top, right, bottom = box
    if angle == 90:
        return [1 - bottom, left, 1 - top, right]
    if angle == 180:
        return [1 - right, 1 - bottom, 1 - left, 1 - top]
    if angle == 270:
        return [top, 1 - right, bottom, 1 - left]
    return box


def adapt(result, width, height):
    preprocessor = result.get("doc_preprocessor_res", {})
    angle = preprocessor.get("angle", 0)
    if angle not in (0, 90, 180, 270):
        raise ValueError("Unexpected document rotation")
    if angle in (90, 270):
        width, height = height, width
    page_box = (0, 0, width, height)
    lines = recognize_lines(result.get("overall_ocr_res"), page_box)
    regions = list(table_regions(result.get("layout_det_res"), page_box))
    raw_tables = sequence(result.get("table_res_list"))
    if len(regions) > MAX_TABLES or len(raw_tables) > MAX_TABLES:
        raise ValueError("Too many detected table regions")
    by_region = {tuple(normalize(region, width, height)): [] for region in regions}
    for raw_table in raw_tables:
        try:
            table = adapt_table(raw_table, regions, page_box, lines)
        except ValueError:
            continue
        by_region[tuple(table["box"])].append(table)
    # Preserve recognized table regions with missing or competing grids for manual review.
    tables = [options[0] if len(options) == 1 else {"box": list(box), "cells": []}
              for box, options in by_region.items()]
    for table in tables:
        table["box"] = input_orientation(table["box"], angle)
        for cell in table["cells"]:
            cell["box"] = input_orientation(cell["box"], angle)
    return {
        "engineVersion": ENGINE_VERSION,
        "rotation": angle,
        "blocks": [
            {"text": text, "box": input_orientation(normalize(box, width, height), angle), "confidence": score}
            for text, box, score in lines
        ],
        "tables": tables,
    }
