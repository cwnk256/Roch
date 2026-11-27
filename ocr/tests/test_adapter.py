import unittest

from adapter import adapt


def sample(table_html, boxes, lines):
    return {
        "overall_ocr_res": {
            "rec_texts": ["Page title"],
            "rec_boxes": [[0, 0, 60, 10]],
            "rec_scores": [0.95],
        },
        "layout_det_res": {"boxes": [
            {"label": "table", "coordinate": [10, 20, 90, 80]},
        ]},
        "table_res_list": [{
            "table_region_id": 1,
            "pred_html": table_html,
            "cell_box_list": boxes,
            "table_ocr_pred": {
                "rec_texts": [line[0] for line in lines],
                "rec_boxes": [line[1] for line in lines],
                "rec_scores": [line[2] for line in lines],
            },
        }],
    }


class AdapterTest(unittest.TestCase):
    def test_shuffled_cell_boxes_and_merged_rows_and_columns(self):
        result = adapt(sample(
            '<html><body><table><thead><tr><td rowspan="2">discard this text</td>'
            '<td colspan="2"></td></tr></thead><tbody><tr><td></td><td></td></tr>'
            '<tr><td></td><td></td><td></td></tr></tbody></table></body></html>',
            [[65, 62, 89, 79], [12, 22, 35, 59], [65, 42, 89, 59],
             [12, 62, 35, 79], [38, 22, 89, 39], [38, 42, 62, 59], [38, 62, 62, 79]],
            [("Right", [70, 43, 84, 49], 0.9),
             ("Header", [54, 25, 74, 37], 0.91),
             ("Shared", [14, 25, 31, 34], 0.86),
             ("Left", [40, 43, 57, 48], 0.89)],
        ), 100, 100)

        self.assertEqual(result["blocks"], [
            {"text": "Page title", "box": [0, 0, 0.6, 0.1], "confidence": 0.95},
        ])
        (table,) = result["tables"]
        self.assertEqual(table["box"], [0.1, 0.2, 0.9, 0.8])
        self.assertEqual([
            (cell["row"], cell["column"], cell["rowSpan"], cell["columnSpan"], cell["text"])
            for cell in table["cells"]
        ], [(0, 0, 2, 1, "Shared"), (0, 1, 1, 2, "Header"),
            (1, 1, 1, 1, "Left"), (1, 2, 1, 1, "Right"),
            (2, 0, 1, 1, ""), (2, 1, 1, 1, ""), (2, 2, 1, 1, "")])
        self.assertEqual(table["cells"][1]["box"], [0.38, 0.22, 0.89, 0.39])
        self.assertEqual(table["cells"][1]["confidence"], 0.91)

    def test_ordinary_cells_are_matched_spatially_and_keep_empty_slots(self):
        result = adapt(sample(
            '<table><tr><td></td><td></td></tr><tr><td></td><td></td></tr></table>',
            [[51, 51, 88, 78], [12, 52, 49, 78], [51, 25, 88, 49], [12, 23, 49, 49]],
            [("90", [55, 63, 66, 69], 0.72), ("Label", [15, 25, 39, 39], 0.93)],
        ), 100, 100)

        (table,) = result["tables"]
        self.assertEqual([cell["text"] for cell in table["cells"]], ["Label", "", "", "90"])
        self.assertEqual(table["cells"][1]["confidence"], 0.0)

    def test_rejects_invalid_table_shapes_but_preserves_text(self):
        cases = [
            ('<table><tr><td></td><td></td></tr></table>', [[15, 25, 42, 55]]),
            ('<table><tr><td rowspan="400"></td></tr></table>', [[15, 25, 42, 55]]),
            ('<table><tr><td onclick="foo"></td></tr></table>', [[15, 25, 42, 55]]),
            ('<table><tr><td><script>x</script></td></tr></table>', [[15, 25, 42, 55]]),
            ('<table><tr><td></td></tr></table>', [[2, 1, 8, 8]]),
            ('<table><tr><td></td><td></td></tr></table>', [[15, 25, 42, 55], [15, 25, 42, 55]]),
            ('<table><tr><td colspan="2"></td></tr><tr><td></td><td></td></tr></table>',
             [[15, 25, 42, 40], [15, 44, 42, 55], [45, 44, 80, 55]]),
        ]
        for table_html, boxes in cases:
            with self.subTest(table=table_html):
                result = adapt(sample(table_html, boxes, []), 100, 100)
                self.assertEqual(result["tables"], [{"box": [0.1, 0.2, 0.9, 0.8], "cells": []}])
                self.assertEqual(result["blocks"][0]["text"], "Page title")

    def test_ambiguous_ocr_line_stays_out_of_neighboring_cells(self):
        result = adapt(sample(
            '<table><tr><td></td><td></td></tr></table>',
            [[10, 24, 49, 78], [51, 24, 90, 78]],
            [("Crosses boundary", [25, 40, 75, 58], 0.93)],
        ), 100, 100)

        (table,) = result["tables"]
        self.assertEqual([cell["text"] for cell in table["cells"]], ["", ""])

    def test_recovers_complete_detected_grid_when_model_markup_misses_row_span(self):
        result = adapt(sample(
            '<table><tr><td></td><td colspan="2"></td></tr>'
            '<tr><td></td><td></td><td></td></tr><tr><td></td><td></td><td></td></tr></table>',
            [[64, 60, 89, 78], [11, 22, 36, 60], [64, 41, 89, 60],
             [11, 60, 36, 78], [36, 22, 89, 41], [36, 41, 64, 60], [36, 60, 64, 78]],
            [("Row span", [14, 25, 29, 35], 0.98),
             ("Column span", [50, 24, 74, 32], 0.95),
             ("Subheading", [39, 45, 55, 51], 0.94)],
        ), 100, 100)

        (table,) = result["tables"]
        self.assertEqual([
            (cell["row"], cell["column"], cell["rowSpan"], cell["columnSpan"], cell["text"])
            for cell in table["cells"][:4]
        ], [(0, 0, 2, 1, "Row span"), (0, 1, 1, 2, "Column span"),
            (1, 1, 1, 1, "Subheading"), (1, 2, 1, 1, "")])

    def test_maps_recognized_boxes_back_to_unrotated_input(self):
        payload = sample(
            '<table><tr><td></td></tr></table>',
            [[12, 22, 89, 78]], [("Total", [20, 31, 55, 42], 0.91)],
        )
        for angle, expected_block in {
            90: [0.9, 0, 1, 0.3],
            180: [0.7, 0.9, 1, 1],
            270: [0, 0.7, 0.1, 1],
        }.items():
            with self.subTest(angle=angle):
                payload["doc_preprocessor_res"] = {"angle": angle}
                width, height = (100, 200) if angle in (90, 270) else (200, 100)
                result = adapt(payload, width, height)
                self.assertEqual(result["rotation"], angle)
                self.assertEqual([round(value, 4) for value in result["blocks"][0]["box"]], expected_block)
                cell = result["tables"][0]["cells"][0]
                self.assertEqual((cell["row"], cell["column"], cell["text"]), (0, 0, "Total"))

    def test_mismatched_page_ocr_vectors_fail_closed(self):
        payload = sample("", [], [])
        payload["overall_ocr_res"]["rec_scores"] = []
        with self.assertRaises(ValueError):
            adapt(payload, 100, 100)


if __name__ == "__main__":
    unittest.main()
