import { readFile, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { renderReceiptLineSvg } from './receiptline-svg.mjs';

const [inputArgument, outputArgument, columnsArgument = '48'] = process.argv.slice(2);
if (!inputArgument || !outputArgument) {
  throw new Error('usage: node render-svg.mjs <input.receiptline> <output.svg> [columns]');
}

const input = await readFile(resolve(inputArgument), 'utf8');
const svg = renderReceiptLineSvg(input, Number(columnsArgument));
await writeFile(resolve(outputArgument), svg, 'utf8');
