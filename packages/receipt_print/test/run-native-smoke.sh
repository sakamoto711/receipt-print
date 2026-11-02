#!/bin/bash
set -euo pipefail
package_dir="$(cd "$(dirname "$0")/.." && pwd)"
repo_dir="$(cd "$package_dir/../.." && pwd)"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/receipt-native.XXXXXX")"
trap 'rm -rf "$work_dir"' EXIT
sdk="$(xcrun --sdk iphonesimulator --show-sdk-path)"
framework="$package_dir/ios/Frameworks/ReceiptPrint.xcframework/ios-arm64-simulator"
if [[ ! -d "$framework" ]]; then
  echo 'Run ./gradlew prepareFlutterIos first.' >&2
  exit 1
fi
if [[ -z "${RECEIPT_SIMULATOR_UDID:-}" ]]; then
  echo 'Set RECEIPT_SIMULATOR_UDID to a booted arm64 iOS simulator.' >&2
  exit 1
fi
input="${1:-$work_dir/input.json}"
output="${2:-$work_dir/output.json}"
if [[ $# -eq 0 ]]; then
  python3 - "$input" <<'PYTHON'
import json, sys
style = dict(widthScale=1, heightScale=1, align='left', emphasized=False, inverted=False)
job = dict(direction='normal', startActions=[], tickets=[dict(cut='partial', children=[
    dict(type='text', value='注文伝票 <script> & 日本語', style=style, wrap='character'),
    dict(type='twoColumn', leading=dict(value='ラーメン', style=style), trailing=dict(value='￥1,200', style=style)),
    dict(type='rule', thickness=1),
    dict(type='qr', data='https://example.invalid/order/123', cellSize=6, align='center'),
    dict(type='feed', lines=1),
])])
models = ['StarFvp10', 'EpsonTmT90Kp', 'EpsonTmM30', 'StarTsp650II', 'StarMcPrint3', 'SiiRpF10']
def canonical(value):
    if isinstance(value, dict):
        for k,v in list(value.items()):
            if k in ['direction','cut','align','wrap'] and isinstance(v,str): value[k] = v[0].upper()+v[1:]
            elif k == 'type' and v == 'qr': value[k] = 'qrCode'; value['errorCorrection'] = 'Medium'
            canonical(value.get(k))
        if value.get('type') == 'text': value['preserveNewLines'] = True
        if value.get('type') == 'twoColumn': value.update(widthColumns=None, leadingReservedColumns=None, trailingReservedColumns=None, overflow='WrapLeadingThenTrailing')
    elif isinstance(value,list):
        for item in value: canonical(item)
canonical(job)
requests = [dict(measure='日本Aｱ😀')] + [dict(job=job, printer=dict(model=m, columnsPerLine=48, compatibilityMode='Compatibility', soundEnabled=False, optionalExternalBuzzerEnabled=False)) for m in models]
with open(sys.argv[1], 'w') as f: json.dump(requests, f, ensure_ascii=False)
PYTHON
fi
SDKROOT="$sdk" xcrun swiftc -target arm64-apple-ios15.0-simulator -sdk "$sdk" \
  -module-cache-path "$work_dir/cache" -F "$framework" \
  -framework ReceiptPrint -framework JavaScriptCore \
  "$package_dir/ios/receipt_print/Sources/receipt_print/ReceiptChannelCodec.swift" \
  "$package_dir/ios/receipt_print/Sources/receipt_print/ReceiptSvgRenderer.swift" \
  "$package_dir/test/NativeReceiptSmoke.swift" -o "$work_dir/smoke"
xcrun simctl spawn "$RECEIPT_SIMULATOR_UDID" "$work_dir/smoke" "$input" \
  "$package_dir/ios/receipt_print/Sources/receipt_print/Resources/receiptline.js" "$output"
python3 - "$input" "$output" <<'PYTHON'
import json, sys, base64
requests = json.load(open(sys.argv[1]))
responses = json.load(open(sys.argv[2]))
assert len(responses) == len(requests)
for request, response in zip(requests, responses):
    if 'measure' in request:
        assert response['columns'] == 6
    else:
        assert base64.b64decode(response['bytes'])
        assert '<svg' in response['svg'] and '<script>' not in response['svg']
        assert 'lang="ja"' in response['svg'] and '@import' not in response['svg']
        assert not any(d['severity'] == 'Error' for d in response['diagnostics'])
print('Validated native results:', len(responses))
PYTHON
