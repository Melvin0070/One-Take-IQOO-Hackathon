import copy
import json
from pathlib import Path
import tempfile
import unittest

import genie_trace


class GenieTraceTests(unittest.TestCase):
    @staticmethod
    def profile(events, *, metadata=None, components=None):
        return {
            "header": {"artifact_type": "GENIE_PROFILE"},
            "metadata": {"timestamp": 123} if metadata is None else metadata,
            "components": [] if components is None else components,
            "traceEvents": events,
        }

    @staticmethod
    def event(name, ts, dur, *, pid=0, tid=7, args=None):
        return {
            "name": name,
            "cat": "function",
            "ph": "X",
            "ts": ts,
            "dur": dur,
            "pid": pid,
            "tid": tid,
            "args": {} if args is None else args,
        }

    @staticmethod
    def slices(trace):
        return [event for event in trace["traceEvents"] if event.get("ph") == "X"]

    def test_crossing_slices_are_split_into_visual_lanes(self):
        profile = self.profile([
            self.event("outer", 0, 10),
            self.event("crossing", 5, 10),
        ])

        trace = genie_trace.convert_profile(profile)
        slices = {event["name"]: event for event in self.slices(trace)}
        self.assertNotEqual(slices["outer"]["tid"], slices["crossing"]["tid"])
        self.assertEqual(slices["outer"]["tid"], 7)
        self.assertEqual(slices["crossing"]["args"]["original_pid"], 0)
        self.assertEqual(slices["crossing"]["args"]["original_tid"], 7)
        names = [event["args"]["name"] for event in trace["traceEvents"]
                 if event.get("ph") == "M" and event.get("name") == "thread_name"]
        self.assertEqual(names, [
            "Genie thread 7 [visual lane 0]",
            "Genie thread 7 [visual lane 1]",
        ])

    def test_nested_slices_stay_on_one_lane(self):
        profile = self.profile([
            self.event("outer", 0, 20),
            self.event("middle", 2, 12),
            self.event("inner", 4, 2),
        ])

        trace = genie_trace.convert_profile(profile)
        self.assertEqual({event["tid"] for event in self.slices(trace)}, {7})

    def test_equal_timestamps_are_sorted_longest_first_for_assignment(self):
        profile = self.profile([
            self.event("long", 10, 20),
            self.event("short", 10, 4),
            self.event("crossing", 12, 10),
        ])

        trace = genie_trace.convert_profile(profile)
        slices = {event["name"]: event for event in self.slices(trace)}
        self.assertEqual(slices["long"]["tid"], 7)
        self.assertEqual(slices["short"]["tid"], 7)
        self.assertNotEqual(slices["crossing"]["tid"], 7)

    def test_finished_intervals_can_reuse_a_lane_at_equal_timestamp(self):
        profile = self.profile([
            self.event("first", 0, 5),
            self.event("second", 5, 5),
        ])

        trace = genie_trace.convert_profile(profile)
        slices = {event["name"]: event for event in self.slices(trace)}
        self.assertEqual(slices["first"]["tid"], slices["second"]["tid"])

    def test_event_count_and_raw_profile_stats_are_preserved(self):
        components = [{"name": "dialog", "type": "dialog", "events": [{"x": 1}]}]
        profile = self.profile([
            self.event("a", 1, 2, args={"stackDepth": 3, "custom": "keep"}),
            self.event("b", 4, 1),
        ], metadata={"timestamp": 42, "extra": {"retain": True}}, components=components)
        original = copy.deepcopy(profile)

        trace = genie_trace.convert_profile(profile)

        self.assertEqual(profile, original)
        self.assertEqual(trace["header"], profile["header"])
        self.assertEqual(trace["metadata"], profile["metadata"])
        self.assertEqual(trace["components"], profile["components"])
        self.assertEqual(len(self.slices(trace)), len(profile["traceEvents"]))
        self.assertEqual(
            {event["name"] for event in self.slices(trace)},
            {event["name"] for event in profile["traceEvents"]},
        )
        self.assertEqual(
            {(event["name"], event["ts"], event["dur"])
             for event in self.slices(trace)},
            {(event["name"], event["ts"], event["dur"])
             for event in profile["traceEvents"]},
        )
        self.assertEqual(trace["genie_trace_conversion"]["source_event_count"], 2)
        self.assertEqual(trace["genie_trace_conversion"]["slice_event_count"], 2)

    def test_conversion_metadata_contains_lane_mapping(self):
        profile = self.profile([
            self.event("a", 0, 10, pid=3, tid=9),
            self.event("b", 2, 10, pid=3, tid=9),
            self.event("other", 0, 1, pid=4, tid=2),
        ])

        trace = genie_trace.convert_profile(profile)
        mapping = trace["genie_trace_conversion"]["lane_mapping"]
        self.assertEqual(mapping[0]["original_pid"], 3)
        self.assertEqual(mapping[0]["original_tid"], 9)
        self.assertEqual([lane["visual_lane"] for lane in mapping[0]["lanes"]], [0, 1])
        self.assertEqual(mapping[0]["lanes"][0]["output_tid"], 9)
        self.assertEqual(mapping[0]["lanes"][1]["output_pid"], 3)
        self.assertEqual(mapping[1]["original_pid"], 4)

    def test_malformed_profile_is_rejected(self):
        for profile in [{}, {"traceEvents": {}}, {"traceEvents": [None]}]:
            with self.subTest(profile=profile), self.assertRaises(ValueError):
                genie_trace.convert_profile(profile)
        for event in [
            self.event("negative", 0, -1),
            {**self.event("missing-ts", 0, 1), "ts": "1"},
            {**self.event("missing-dur", 0, 1), "dur": True},
        ]:
            with self.subTest(event=event), self.assertRaises(ValueError):
                genie_trace.convert_profile(self.profile([event]))

    def test_cli_requires_distinct_input_and_output_files(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "profile.json"
            path.write_text(json.dumps(self.profile([])) + "\n", encoding="utf-8")
            with self.assertRaises(SystemExit) as raised:
                genie_trace.main([str(path), str(path)])
            self.assertEqual(raised.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
