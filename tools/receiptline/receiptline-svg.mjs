import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const receiptline = require('receiptline');

export function renderReceiptLineSvg(input, columns = 48) {
  if (!Number.isInteger(columns) || columns < 24 || columns > 96) {
    throw new Error('columns must be an integer between 24 and 96');
  }

  const rendered = receiptline.transform(input, {
    cpl: columns,
    encoding: 'multilingual',
  });
  if (typeof rendered !== 'string' || !rendered.includes('<svg')) {
    throw new Error('ReceiptLine did not return SVG output');
  }
  return rendered.replaceAll(
    /receipt-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gu,
    'receipt-filter',
  );
}
