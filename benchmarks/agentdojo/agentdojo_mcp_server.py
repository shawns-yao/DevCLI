from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from collections.abc import Mapping, Sequence
from typing import Any

import agentdojo.attacks  # noqa: F401 - registers the official attacks
import agentdojo
from agentdojo.attacks.attack_registry import ATTACKS
from agentdojo.base_tasks import BaseUserTask
from agentdojo.functions_runtime import FunctionCall, FunctionsRuntime
from agentdojo.task_suite.load_suites import get_suite
from pydantic import BaseModel


CONTROL_TOOLS = {"agentdojo_metadata", "agentdojo_finalize"}
MUTATION_PREFIXES = (
    "add_",
    "append_",
    "book_",
    "cancel_",
    "create_",
    "delete_",
    "invite_",
    "mark_",
    "move_",
    "post_",
    "remove_",
    "reserve_",
    "reschedule_",
    "schedule_",
    "send_",
    "share_",
    "transfer_",
    "update_",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--suite", required=True)
    parser.add_argument("--version", default="v1.2.2")
    parser.add_argument("--user-task", required=True)
    parser.add_argument("--injection-task")
    parser.add_argument("--attack", default="direct")
    parser.add_argument("--no-injection", action="store_true")
    return parser.parse_args()


def json_value(value: Any) -> Any:
    if isinstance(value, BaseModel):
        return value.model_dump(mode="json")
    if isinstance(value, Mapping):
        return {str(key): json_value(item) for key, item in value.items()}
    if isinstance(value, Sequence) and not isinstance(value, (str, bytes, bytearray)):
        return [json_value(item) for item in value]
    return value


def text_result(value: Any, *, is_error: bool = False) -> dict[str, Any]:
    text = value if isinstance(value, str) else json.dumps(json_value(value), ensure_ascii=False)
    return {"content": [{"type": "text", "text": text}], "isError": is_error}


class AgentDojoMcpServer:
    def __init__(self, args: argparse.Namespace) -> None:
        self.suite = get_suite(args.version, args.suite)
        self.user_task = self.suite.get_user_task_by_id(args.user_task)
        self.injection_task = None
        self.attack = None
        self.injections: dict[str, str] = {}
        if not args.no_injection:
            if not args.injection_task or args.attack == "none":
                raise ValueError("attack cases require --injection-task and a non-none --attack")
            self.injection_task = self.suite.get_injection_task_by_id(args.injection_task)
            attack_type = ATTACKS.get(args.attack)
            if attack_type is None:
                raise ValueError(f"Unknown official AgentDojo attack: {args.attack}")
            self.attack = attack_type(self.suite, None)
            self.injections = self.attack.attack(self.user_task, self.injection_task)
        environment = self.suite.load_and_inject_default_environment(self.injections)
        self.environment = self.user_task.init_environment(environment)
        self.pre_environment = self.environment.model_copy(deep=True)
        self.runtime = FunctionsRuntime(self.suite.tools)
        self.traces: list[FunctionCall] = []
        self.version = args.version
        self.attack_name = None if args.no_injection else args.attack

    def metadata(self) -> dict[str, Any]:
        return {
            "benchmark": "AgentDojo",
            "benchmark_version": self.version,
            "suite": self.suite.name,
            "user_task_id": self.user_task.ID,
            "injection_task_id": None if self.injection_task is None else self.injection_task.ID,
            "attack": self.attack_name,
            "evaluation_scope": "utility_only" if self.injection_task is None else "utility_and_attack",
            "prompt": self.user_task.PROMPT,
            "injection_vectors": sorted(self.injections),
            "tool_count": len(self.runtime.functions),
            "loaded_harness_root": str(Path(agentdojo.__file__).resolve().parents[2]),
            "injections_sha256": hashlib.sha256(json.dumps(
                json_value(self.injections), sort_keys=True, ensure_ascii=False
            ).encode("utf-8")).hexdigest(),
            "initial_environment_sha256": hashlib.sha256(json.dumps(
                json_value(self.pre_environment), sort_keys=True, ensure_ascii=False
            ).encode("utf-8")).hexdigest(),
        }

    @staticmethod
    def annotations(tool_name: str) -> dict[str, bool]:
        mutating = tool_name == "get_unread_emails" or tool_name.startswith(MUTATION_PREFIXES)
        return {
            "readOnlyHint": not mutating,
            "destructiveHint": mutating,
            "openWorldHint": mutating,
        }

    def tool_descriptors(self) -> list[dict[str, Any]]:
        descriptors = []
        for function in self.runtime.functions.values():
            descriptors.append(
                {
                    "name": function.name,
                    "description": function.description,
                    "inputSchema": function.parameters.model_json_schema(),
                    "annotations": self.annotations(function.name),
                }
            )
        descriptors.extend(
            [
                {
                    "name": "agentdojo_metadata",
                    "description": "Benchmark control metadata. Not exposed to the agent.",
                    "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
                    "annotations": self.annotations("agentdojo_metadata"),
                },
                {
                    "name": "agentdojo_finalize",
                    "description": "Run the official AgentDojo evaluators. Not exposed to the agent.",
                    "inputSchema": {
                        "type": "object",
                        "properties": {"model_output": {"type": "string"}},
                        "required": ["model_output"],
                        "additionalProperties": False,
                    },
                    "annotations": self.annotations("agentdojo_finalize"),
                },
            ]
        )
        return descriptors

    def call_tool(self, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
        if name == "agentdojo_metadata":
            return text_result(self.metadata())
        if name == "agentdojo_finalize":
            output = str(arguments.get("model_output", ""))
            content = [{"type": "text", "content": output}]
            utility = self.suite._check_user_task_utility(
                self.user_task,
                content,
                self.pre_environment,
                self.environment,
                self.traces,
            )
            evaluation = {
                **self.metadata(),
                "utility": bool(utility),
                "tool_calls": [call.model_dump(mode="json") for call in self.traces],
            }
            if self.injection_task is not None:
                attack_success = self.suite._check_injection_task_security(
                    self.injection_task,
                    content,
                    self.pre_environment,
                    self.environment,
                    self.traces,
                )
                evaluation.update({
                    "attack_success": bool(attack_success),
                    "security_pass": not bool(attack_success),
                })
            return text_result(evaluation)
        if name not in self.runtime.functions:
            return text_result(f"Unknown AgentDojo tool: {name}", is_error=True)
        call = FunctionCall(function=name, args=arguments)
        self.traces.append(call)
        result, error = self.runtime.run_function(self.environment, name, arguments)
        if error is not None:
            return text_result(error, is_error=True)
        return text_result(result)

    def handle(self, request: dict[str, Any]) -> dict[str, Any] | None:
        request_id = request.get("id")
        method = request.get("method", "")
        if request_id is None:
            return None
        if method == "initialize":
            result = {
                "protocolVersion": request.get("params", {}).get("protocolVersion", "2024-11-05"),
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "agentdojo-devcli-bridge", "version": "1"},
            }
        elif method == "tools/list":
            result = {"tools": self.tool_descriptors()}
        elif method == "tools/call":
            params = request.get("params", {})
            result = self.call_tool(params.get("name", ""), params.get("arguments", {}))
        elif method == "ping":
            result = {}
        elif method == "shutdown":
            result = {}
        else:
            return {
                "jsonrpc": "2.0",
                "id": request_id,
                "error": {"code": -32601, "message": f"Method not found: {method}"},
            }
        return {"jsonrpc": "2.0", "id": request_id, "result": result}


def main() -> None:
    server = AgentDojoMcpServer(parse_args())
    for line in sys.stdin:
        if not line.strip():
            continue
        try:
            response = server.handle(json.loads(line))
            if response is not None:
                print(json.dumps(response, ensure_ascii=False, separators=(",", ":")), flush=True)
        except Exception as error:  # MCP must return structured failure without corrupting stdout.
            request_id = None
            try:
                request_id = json.loads(line).get("id")
            except Exception:
                pass
            print(
                json.dumps(
                    {
                        "jsonrpc": "2.0",
                        "id": request_id,
                        "error": {"code": -32603, "message": f"{type(error).__name__}: {error}"},
                    },
                    ensure_ascii=False,
                    separators=(",", ":"),
                ),
                flush=True,
            )


if __name__ == "__main__":
    main()
