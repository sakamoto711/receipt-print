import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { renderReceiptLineSvg } from './receiptline-svg.mjs';

const [inputArgument, goldenArgument, columnsArgument = '48'] = process.argv.slice(2);
if (!inputArgument || !goldenArgument) {
  throw new Error('usage: node verify-svg.mjs <input.receiptline> <golden.svg> [columns]');
}

const [input, golden] = await Promise.all([
  readFile(resolve(inputArgument), 'utf8'),
  readFile(resolve(goldenArgument), 'utf8'),
]);
const actual = renderReceiptLineSvg(input, Number(columnsArgument));
if (actual !== golden) {
  throw new Error('ReceiptLine SVG golden differs; run the explicit render command to inspect it');
}
