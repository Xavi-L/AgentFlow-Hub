#!/usr/bin/env python3
"""Bounded GLM-5.2 decision replay, never a tool execution or an E2E acceptance.

Paired mode makes six independent requests in A,B,B,A,A,B order. Candidate mode
makes only two B requests, including for an explicitly sourced two-result state.
Only B appends candidate instructions to the second original SYSTEM message.
Provider credentials must be injected through OPENAI_API_KEY; they are never read
from IDEA or written to evidence. No provider failure is retried.
"""
import argparse
from copy import deepcopy
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import socket
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


MODEL = 'glm-5.2'
BASE_URL = 'https://open.bigmodel.cn/api/paas/v4'
OUTPUT_CAP = 8192
TOTAL_TOKEN_BUDGET = 60000
TIMEOUT_SECONDS = 120
MAX_RESPONSE_BYTES = 1024 * 1024
TOOL_CODES = {'order_query', 'payment_log_query'}
BOUNDARY = ('Independent real-provider decision replay only. No tool, browser, '
            'embedding, Qdrant, final generation or task mutation is executed. '
            'Replay results are not application E2E acceptance or a reliability estimate.')


class ReplayError(Exception):
    """An intentionally bounded, credential-free diagnostic code."""


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None


def require(condition, code):
    if not condition:
        raise ReplayError(code)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'DUPLICATE_JSON_KEY')
        result[key] = value
    return result


def strict_json(text):
    try:
        return json.loads(text, object_pairs_hook=unique_object,
                          parse_constant=lambda _value: (_ for _ in ()).throw(ReplayError('NONFINITE_JSON_NUMBER')))
    except (ValueError, TypeError, UnicodeError, RecursionError) as error:
        raise ReplayError('INVALID_JSON') from error


def read_json(path, limit=4 * 1024 * 1024):
    require(path.is_file() and 0 < path.stat().st_size <= limit, 'INVALID_SOURCE_FILE')
    try:
        return strict_json(path.read_text(encoding='utf-8'))
    except (OSError, UnicodeError) as error:
        raise ReplayError('SOURCE_READ_FAILED') from error


def encode_json(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode('utf-8')


def safe_write(path, value, api_key=''):
    serialized = json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n'
    if api_key:
        # A provider must not be able to reflect the injected credential into an artifact.
        serialized = serialized.replace(api_key, '[REDACTED]')
    path.write_text(serialized, encoding='utf-8')
    path.chmod(0o600)


def integer(value):
    return type(value) is int and -(2 ** 63) <= value < 2 ** 63


def nonblank(value):
    return isinstance(value, str) and bool(value.strip())


def required_fields(value):
    require(isinstance(value, list) and all(nonblank(item) for item in value), 'UNSUPPORTED_TOOL_SCHEMA')
    return set(value)


def validate_schema(schema, top_level=True):
    """Exact used V27/V28 subset; unknown capabilities are rejected, not ignored."""
    require(isinstance(schema, dict), 'UNSUPPORTED_TOOL_SCHEMA')
    common = {'type', 'description', 'default', 'title'}
    kind = schema.get('type')
    allowed = {
        'object': common | {'properties', 'required', 'additionalProperties', 'anyOf'},
        'string': common | {'minLength', 'maxLength'},
        'integer': common | {'minimum', 'maximum'},
    }
    require(kind in allowed and set(schema) <= allowed[kind], 'UNSUPPORTED_TOOL_SCHEMA')
    if kind == 'object':
        properties = schema.get('properties', {})
        require(isinstance(properties, dict), 'UNSUPPORTED_TOOL_SCHEMA')
        required_fields(schema.get('required', []))
        require(type(schema.get('additionalProperties', True)) is bool, 'UNSUPPORTED_TOOL_SCHEMA')
        for child in properties.values():
            validate_schema(child, False)
        if 'anyOf' in schema:
            branches = schema['anyOf']
            require(top_level and isinstance(branches, list) and bool(branches), 'UNSUPPORTED_TOOL_SCHEMA')
            for branch in branches:
                require(isinstance(branch, dict) and set(branch) == {'required'}, 'UNSUPPORTED_TOOL_SCHEMA')
                names = required_fields(branch['required'])
                require(bool(names) and names <= set(properties), 'UNSUPPORTED_TOOL_SCHEMA')
    else:
        lower, upper = ('minLength', 'maxLength') if kind == 'string' else ('minimum', 'maximum')
        for key in (lower, upper):
            require(key not in schema or integer(schema[key]), 'UNSUPPORTED_TOOL_SCHEMA')
            if kind == 'string' and key in schema:
                require(schema[key] >= 0, 'UNSUPPORTED_TOOL_SCHEMA')
        require(lower not in schema or upper not in schema or schema[lower] <= schema[upper],
                'UNSUPPORTED_TOOL_SCHEMA')


def validate_arguments(schema, value, top_level=True):
    kind = schema['type']
    if kind == 'object':
        require(isinstance(value, dict), 'INVALID_TOOL_ARGUMENTS')
        properties = schema.get('properties', {})
        require(all(name in value and value[name] is not None for name in schema.get('required', [])),
                'INVALID_TOOL_ARGUMENTS')
        if schema.get('additionalProperties') is False:
            require(set(value) <= set(properties), 'INVALID_TOOL_ARGUMENTS')
        for name, child in value.items():
            if name in properties:
                validate_arguments(properties[name], child, False)
        if top_level and 'anyOf' in schema:
            require(any(all(name in value and value[name] is not None for name in branch['required'])
                        for branch in schema['anyOf']), 'INVALID_TOOL_ARGUMENTS')
    elif kind == 'string':
        require(isinstance(value, str), 'INVALID_TOOL_ARGUMENTS')
        require(len(value) >= schema.get('minLength', 0), 'INVALID_TOOL_ARGUMENTS')
        require(len(value) <= schema.get('maxLength', 2 ** 63 - 1), 'INVALID_TOOL_ARGUMENTS')
        require(schema.get('minLength', 0) <= 0 or bool(value.strip()), 'INVALID_TOOL_ARGUMENTS')
    elif kind == 'integer':
        require(integer(value), 'INVALID_TOOL_ARGUMENTS')
        require(value >= schema.get('minimum', -(2 ** 63)), 'INVALID_TOOL_ARGUMENTS')
        require(value <= schema.get('maximum', 2 ** 63 - 1), 'INVALID_TOOL_ARGUMENTS')


def validate_messages(messages):
    require(isinstance(messages, list) and len(messages) == 3, 'INVALID_SOURCE_MESSAGES')
    for message in messages:
        require(isinstance(message, dict) and set(message) == {'role', 'content'}, 'INVALID_SOURCE_MESSAGES')
        require(message['role'] in ('SYSTEM', 'USER', 'ASSISTANT') and nonblank(message['content']),
                'INVALID_SOURCE_MESSAGES')
    require([item['role'] for item in messages] == ['SYSTEM', 'SYSTEM', 'USER'], 'INVALID_SOURCE_SYSTEM_ORDER')
    require(len(encode_json(messages)) <= 65536, 'SOURCE_MESSAGES_TOO_LARGE')


def user_payload(messages):
    users = [item for item in messages if item['role'] == 'USER']
    require(len(users) == 1, 'EXPECTED_ONE_SOURCE_USER_PAYLOAD')
    payload = strict_json(users[0]['content'])
    require(isinstance(payload, dict) and isinstance(payload.get('observations'), list),
            'INVALID_SOURCE_USER_PAYLOAD')
    return payload


def validate_state(messages, schemas, expected):
    payload = user_payload(messages)
    require('order_1024' in str(payload.get('userTask', '')), 'SOURCE_ORDER_MISMATCH')
    available = payload.get('availableTools')
    require(isinstance(available, list) and len(available) == len(schemas), 'SOURCE_TOOLS_MISMATCH')
    declared = {tool.get('toolCode'): tool.get('inputSchema') for tool in available if isinstance(tool, dict)}
    require(declared == schemas, 'SOURCE_TOOLS_MISMATCH')
    observations = payload['observations']
    observed = {item.get('toolCode') for item in observations
                if isinstance(item, dict) and item.get('type') == 'UNTRUSTED_TOOL_RESULT'}
    order_results = [item.get('data') for item in observations
                     if isinstance(item, dict) and item.get('toolCode') == 'order_query']
    require(bool(order_results) and all(isinstance(item, dict) and item.get('orderNo') == 'order_1024'
                                       for item in order_results), 'SOURCE_ORDER_RESULT_MISMATCH')
    if expected == 'CALL_PAYMENT':
        require(observed == {'order_query'}, 'EXPECTED_ONLY_ORDER_RESULT')
    else:
        require(TOOL_CODES <= observed, 'EXPECTED_BOTH_TOOL_RESULTS')


def load_source(path, decision_index):
    evidence = read_json(path)
    require(isinstance(evidence, dict) and evidence.get('schemaVersion') == 1, 'INVALID_SOURCE_EVIDENCE')
    trace = evidence.get('trace', {})
    require(isinstance(trace, dict), 'INVALID_SOURCE_TRACE')
    snapshot = trace.get('executionSnapshot', {})
    require(isinstance(snapshot, dict), 'INVALID_SOURCE_SNAPSHOT')
    chat = snapshot.get('chatModel', {})
    require(isinstance(chat, dict) and chat.get('model') == MODEL
            and chat.get('provider') == 'openai-compatible', 'SOURCE_MODEL_MISMATCH')
    require(integer(chat.get('contextWindow')) and chat['contextWindow'] > OUTPUT_CAP,
            'INVALID_SOURCE_CONTEXT_WINDOW')
    calls = []
    steps = trace.get('steps')
    require(isinstance(steps, list) and all(isinstance(step, dict) for step in steps), 'INVALID_SOURCE_STEPS')
    for step in sorted(steps, key=lambda item: item.get('stepIndex', -1)):
        require(isinstance(step.get('llmCalls'), list), 'INVALID_SOURCE_CALLS')
        calls.extend(call for call in step['llmCalls']
                     if isinstance(call, dict) and call.get('callType') == 'DECISION')
    require(1 <= decision_index <= len(calls), 'SOURCE_DECISION_NOT_FOUND')
    call = calls[decision_index - 1]
    request = call.get('requestSnapshot')
    require(isinstance(request, dict) and request.get('modelProvider') == 'openai-compatible'
            and request.get('modelName') == MODEL and call.get('requestedModel') == MODEL,
            'SOURCE_MODEL_MISMATCH')
    require(request.get('responseFormat') == 'json_object' and request.get('thinkingMode') == 'disabled'
            and request.get('responseSchema') is None and request.get('maxOutputTokens') == OUTPUT_CAP,
            'SOURCE_REQUEST_OPTIONS_MISMATCH')
    for name, low, high in (('temperature', 0, 2), ('topP', 0, 1)):
        value = request.get(name)
        require(type(value) in (int, float) and math.isfinite(value) and low <= value <= high,
                'INVALID_SOURCE_SAMPLING')
    require(request['topP'] > 0, 'INVALID_SOURCE_SAMPLING')
    validate_messages(request.get('messages'))
    frozen_tools = snapshot.get('tools')
    require(isinstance(frozen_tools, list) and len(frozen_tools) == 2, 'SOURCE_TOOLS_MISMATCH')
    schemas = {tool.get('toolCode'): tool.get('inputSchema') for tool in frozen_tools if isinstance(tool, dict)}
    require(set(schemas) == TOOL_CODES, 'SOURCE_TOOLS_MISMATCH')
    for schema in schemas.values():
        validate_schema(schema)
    provenance = {'sourceEvidence': str(path), 'sourceTaskId': evidence.get('taskId'),
                  'sourceCallId': call.get('id'), 'sourceProviderRequestId': call.get('providerRequestId'),
                  'decisionIndex': decision_index, 'sourceContextWindow': chat['contextWindow'],
                  'inputKind': 'persisted_request_snapshot'}
    return deepcopy(request), schemas, provenance


def prepare_inputs(args):
    source = args.source_evidence.resolve()
    request, schemas, provenance = load_source(source, args.decision_index)
    messages = request['messages']
    if args.state_input:
        state_path = args.state_input.resolve()
        state = read_json(state_path, 131072)
        fields = {'schemaVersion', 'description', 'sourceEvidenceFiles', 'messages'}
        require(isinstance(state, dict) and set(state) == fields and state['schemaVersion'] == 1,
                'INVALID_STATE_INPUT')
        require(nonblank(state['description']) and len(state['description']) <= 2048, 'INVALID_STATE_INPUT')
        sources = state['sourceEvidenceFiles']
        require(isinstance(sources, list) and bool(sources)
                and all(isinstance(item, str) and Path(item).is_absolute() and Path(item).is_file()
                        for item in sources), 'INVALID_STATE_PROVENANCE')
        messages = state['messages']
        validate_messages(messages)
        provenance.update(inputKind='explicit_state_input', stateInput=str(state_path),
                          stateDescription=state['description'], stateSourceEvidenceFiles=sources)
    validate_state(messages, schemas, args.expected)
    candidate_path = args.candidate_instructions.resolve()
    require(candidate_path.is_file() and 0 < candidate_path.stat().st_size <= 8192, 'INVALID_CANDIDATE_FILE')
    candidate = candidate_path.read_text(encoding='utf-8')
    require(nonblank(candidate), 'INVALID_CANDIDATE_FILE')
    variant_b = deepcopy(messages)
    variant_b[1]['content'] += '\n' + candidate
    validate_messages(variant_b)
    require(all(estimated_input_tokens(items) + OUTPUT_CAP <= provenance['sourceContextWindow']
                for items in (messages, variant_b)), 'FROZEN_CONTEXT_WINDOW_EXCEEDED')
    options = {'model': MODEL, 'temperature': request['temperature'], 'top_p': request['topP'],
               'max_tokens': OUTPUT_CAP, 'response_format': {'type': 'json_object'},
               'thinking': {'type': 'disabled'}, 'stream': False, 'n': 1}
    return {'schemaVersion': 1, 'boundary': BOUNDARY, 'source': provenance,
            'candidateInstructionsFile': str(candidate_path), 'candidateInstructions': candidate,
            'requestOptions': options, 'toolInputSchemas': schemas,
            'variants': {'A': messages, 'B': variant_b}}


def estimated_input_tokens(messages):
    # Identical conservative framing convention to TaskTokenEstimator.
    return 16 + sum(8 + max(1, len(item['content'].encode('utf-8'))) for item in messages)


def java_text_length(text):
    # AgentDecisionParser bounds String.length(), unlike schema string code-point limits.
    return len(text.encode('utf-16-le')) // 2


def parse_decision(content, schemas):
    require(nonblank(content), 'AGENT_INVALID_DECISION')
    root = strict_json(content)
    require(isinstance(root, dict), 'AGENT_INVALID_DECISION')
    if root.get('type') == 'CALL_TOOL':
        require(set(root) == {'type', 'toolCode', 'arguments', 'reason'}, 'AGENT_INVALID_DECISION')
        require(root['toolCode'] in schemas and isinstance(root['arguments'], dict), 'AGENT_INVALID_DECISION')
        reason = root['reason']
        require(nonblank(reason) and java_text_length(reason) <= 256, 'AGENT_INVALID_DECISION')
        validate_arguments(schemas[root['toolCode']], root['arguments'])
        # reason is validated above and intentionally not returned or persisted.
        return {key: deepcopy(root[key]) for key in ('type', 'toolCode', 'arguments')}
    require(root.get('type') == 'FINISH' and set(root) == {'type', 'answerPlan'}, 'AGENT_INVALID_DECISION')
    require(nonblank(root['answerPlan']) and java_text_length(root['answerPlan']) <= 2048,
            'AGENT_INVALID_DECISION')
    return {'type': 'FINISH', 'answerPlan': root['answerPlan']}


def known_usage(value):
    require(isinstance(value, dict), 'UNKNOWN_TOKEN_USAGE')
    fields = ('prompt_tokens', 'completion_tokens', 'total_tokens')
    require(all(integer(value.get(key)) and value[key] >= 0 for key in fields), 'UNKNOWN_TOKEN_USAGE')
    require(value['prompt_tokens'] + value['completion_tokens'] == value['total_tokens']
            and value['total_tokens'] > 0, 'UNKNOWN_TOKEN_USAGE')
    return {key: value[key] for key in fields}


def expected_decision(decision, expected):
    if expected == 'FINISH':
        return decision.get('type') == 'FINISH'
    return (decision.get('type') == 'CALL_TOOL' and decision.get('toolCode') == 'payment_log_query'
            and decision.get('arguments', {}).get('orderNo') == 'order_1024')


def safe_identifier(value, code):
    require(isinstance(value, str) and bool(re.fullmatch(r'[A-Za-z0-9._:/-]{1,256}', value)), code)
    return value


def response_result(body, latency_ms, schemas, expected):
    require(isinstance(body, dict), 'MALFORMED_PROVIDER_RESPONSE')
    result = {'latencyMs': latency_ms, 'usage': known_usage(body.get('usage'))}
    result['model'] = safe_identifier(body.get('model'), 'MALFORMED_PROVIDER_MODEL')
    result['providerRequestId'] = safe_identifier(body.get('id'), 'MALFORMED_PROVIDER_REQUEST_ID')
    require(result['model'] == MODEL, 'RESOLVED_MODEL_MISMATCH')
    choices = body.get('choices')
    require(isinstance(choices, list) and len(choices) == 1 and isinstance(choices[0], dict),
            'MALFORMED_PROVIDER_CHOICES')
    choice = choices[0]
    result['finishReason'] = safe_identifier(choice.get('finish_reason'), 'MALFORMED_FINISH_REASON')
    message = choice.get('message')
    require(isinstance(message, dict), 'MALFORMED_PROVIDER_MESSAGE')
    content = message.get('content')
    result['contentLength'] = len(content) if isinstance(content, str) else 0
    result['contentUtf8Bytes'] = len(content.encode('utf-8')) if isinstance(content, str) else 0
    # No raw response, reasoning_content, reason, headers or provider error body is retained.
    result.update(decisionValid=False, expectedDecision=False)
    try:
        decision = parse_decision(content, schemas)
        result['decision'] = decision
        result['decisionValid'] = True
        result['expectedDecision'] = result['finishReason'] == 'stop' and expected_decision(decision, expected)
    except (ReplayError, TypeError, UnicodeError, RecursionError):
        result['decisionError'] = 'AGENT_INVALID_DECISION'
    return result


def summarize(report):
    report['groups'] = {}
    for variant in ('A', 'B'):
        calls = [item for item in report['calls'] if item['variant'] == variant]
        report['groups'][variant] = {
            'attempted': len(calls), 'decisionValid': sum(bool(item.get('decisionValid')) for item in calls),
            'expectedDecision': sum(bool(item.get('expectedDecision')) for item in calls),
            'actualTokens': sum(item.get('usage', {}).get('total_tokens', 0) for item in calls)}
    report['pairs'] = []
    if report['mode'] == 'paired':
        for index in range(3):
            calls = [item for item in report['calls'] if item['pairIndex'] == index + 1]
            report['pairs'].append({'pairIndex': index + 1,
                                    'completed': len(calls) == 2 and all(item.get('status') == 'OBSERVED' for item in calls),
                                    'results': [{'variant': item['variant'],
                                                 'decisionValid': item.get('decisionValid', False),
                                                 'expectedDecision': item.get('expectedDecision', False)}
                                                for item in calls]})
    report['allExpectedDecisions'] = (report['status'] == 'COMPLETED'
                                     and len(report['calls']) == len(report['plannedOrder'])
                                     and all(item.get('expectedDecision') for item in report['calls']))


def run_replay(args, inputs, api_key, opener=None):
    base = os.environ.get('OPENAI_BASE_URL', BASE_URL).rstrip('/')
    parsed = urlsplit(base)
    require(base == BASE_URL and parsed.scheme == 'https' and not parsed.username
            and not parsed.password and not parsed.query and not parsed.fragment, 'UNSUPPORTED_PROVIDER_ENDPOINT')
    require(nonblank(api_key) and not any(char.isspace() for char in api_key), 'MISSING_OR_INVALID_OPENAI_API_KEY')
    require(api_key not in encode_json(inputs).decode('utf-8'), 'CREDENTIAL_IN_INPUT')
    order = ['A', 'B', 'B', 'A', 'A', 'B'] if args.mode == 'paired' else ['B', 'B']
    report = {'schemaVersion': 1, 'status': 'RUNNING', 'boundary': BOUNDARY,
              'startedAt': datetime.now(timezone.utc).isoformat(), 'mode': args.mode,
              'expected': args.expected, 'source': inputs['source'], 'plannedOrder': order,
              'budget': {'maximumCalls': len(order), 'maximumTotalTokens': TOTAL_TOKEN_BUDGET,
                         'perCallOutputCap': OUTPUT_CAP, 'timeoutSeconds': TIMEOUT_SECONDS,
                         'automaticRetries': 0, 'actualTokens': 0, 'usageComplete': True}, 'calls': []}
    safe_write(args.output_dir / 'inputs.json', inputs, api_key)
    safe_write(args.output_dir / 'report.json', report, api_key)
    opener = opener or build_opener(NoRedirect())
    for index, variant in enumerate(order):
        messages = inputs['variants'][variant]
        estimate = estimated_input_tokens(messages)
        needed = estimate + OUTPUT_CAP
        if report['budget']['actualTokens'] + needed > TOTAL_TOKEN_BUDGET:
            report.update(status='STOPPED', stopReason='INSUFFICIENT_REMAINING_TOKEN_BUDGET')
            report['nextRequestReservation'] = needed
            break
        call = {'index': index + 1, 'variant': variant, 'pairIndex': index // 2 + 1,
                'estimatedInputTokens': estimate, 'reservedMaximumTokens': needed,
                'maxOutputTokens': OUTPUT_CAP}
        report['calls'].append(call)
        # Persist attempt intent before I/O. An uncertain write is never automatically replayed.
        safe_write(args.output_dir / 'report.json', report, api_key)
        wire = dict(inputs['requestOptions'], messages=[{'role': item['role'].lower(), 'content': item['content']}
                                                      for item in messages])
        started = time.monotonic()
        try:
            request = Request(base + '/chat/completions', data=encode_json(wire), method='POST',
                              headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + api_key})
            with opener.open(request, timeout=TIMEOUT_SECONDS) as response:
                call['httpStatus'] = response.status
                raw = response.read(MAX_RESPONSE_BYTES + 1)
                require(response.status == 200, 'UNEXPECTED_HTTP_STATUS')
                require(len(raw) <= MAX_RESPONSE_BYTES, 'PROVIDER_RESPONSE_TOO_LARGE')
            body = strict_json(raw.decode('utf-8'))
            # Account known usage even when the remainder of the response is malformed.
            usage = known_usage(body.get('usage') if isinstance(body, dict) else None)
            call['usage'] = usage
            report['budget']['actualTokens'] += usage['total_tokens']
            call.update(response_result(body, round((time.monotonic() - started) * 1000),
                                        inputs['toolInputSchemas'], args.expected))
            if report['budget']['actualTokens'] > TOTAL_TOKEN_BUDGET or usage['completion_tokens'] > OUTPUT_CAP:
                raise ReplayError('PROVIDER_EXCEEDED_TOKEN_BUDGET')
            call['status'] = 'OBSERVED'
        except HTTPError as error:
            call.update(status='FAILED', httpStatus=error.code, error='PROVIDER_HTTP_ERROR')
            report.update(status='STOPPED', stopReason='PROVIDER_HTTP_ERROR')
        except (URLError, TimeoutError, socket.timeout, OSError):
            call.update(status='FAILED', error='PROVIDER_TRANSPORT_ERROR')
            report.update(status='STOPPED', stopReason='PROVIDER_TRANSPORT_ERROR')
        except (ReplayError, UnicodeError, ValueError, TypeError, RecursionError) as error:
            code = str(error) if isinstance(error, ReplayError) else 'MALFORMED_PROVIDER_RESPONSE'
            call.update(status='FAILED', error=code)
            report.update(status='STOPPED', stopReason=code)
        call.setdefault('latencyMs', round((time.monotonic() - started) * 1000))
        if 'usage' not in call:
            report['budget']['usageComplete'] = False
        summarize(report)
        safe_write(args.output_dir / 'report.json', report, api_key)
        if report['status'] == 'STOPPED':
            break
    if report['status'] == 'RUNNING':
        report['status'] = 'COMPLETED'
    report['finishedAt'] = datetime.now(timezone.utc).isoformat()
    summarize(report)
    safe_write(args.output_dir / 'report.json', report, api_key)
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-evidence', type=Path, required=True)
    parser.add_argument('--candidate-instructions', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--decision-index', type=int, default=2, help='One-based persisted DECISION index; default 2')
    parser.add_argument('--mode', choices=('paired', 'candidate'), default='paired')
    parser.add_argument('--expected', choices=('CALL_PAYMENT', 'FINISH'), default='CALL_PAYMENT')
    parser.add_argument('--state-input', type=Path,
                        help='Explicit messages with schemaVersion, description and sourceEvidenceFiles provenance')
    args = parser.parse_args(argv)
    os.umask(0o077)
    args.output_dir = args.output_dir.resolve()
    try:
        # An existing directory is deliberately not resumable, even if it appears empty.
        args.output_dir.mkdir(parents=True, exist_ok=False)
        args.output_dir.chmod(0o700)
    except OSError:
        print('V47 decision replay BLOCKED: OUTPUT_DIRECTORY_MUST_BE_FRESH', file=sys.stderr)
        return 2
    api_key = os.environ.get('OPENAI_API_KEY', '').strip()
    try:
        inputs = prepare_inputs(args)
        report = run_replay(args, inputs, api_key)
    except (ReplayError, OSError, UnicodeError, ValueError, TypeError, KeyError) as error:
        code = str(error) if isinstance(error, ReplayError) else 'INVALID_LOCAL_REPLAY_INPUT'
        report = {'schemaVersion': 1, 'status': 'BLOCKED', 'boundary': BOUNDARY, 'error': code,
                  'automaticRetries': 0, 'calls': []}
        safe_write(args.output_dir / 'report.json', report, api_key)
    print(f"V47 decision replay {report['status']}: {args.output_dir / 'report.json'}")
    return 0 if report['status'] == 'COMPLETED' else 2


if __name__ == '__main__':
    sys.exit(main())
