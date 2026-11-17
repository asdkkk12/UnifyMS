#!/usr/bin/env python3
"""Render an existing .req, or export a saved representative then render it."""
import argparse
import sys
from pathlib import Path
from merm_model import parse_req, export_req, write_json
from merm_view import render_svg, render_html


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument('--input', type=Path, help='Existing .req')
    inputs.add_argument('--run', type=Path, help='Formal result JSON containing representative and run')
    parser.add_argument('--source', type=Path, help='Original .remodel or Java directory, required with --run')
    parser.add_argument('--snapshot', type=Path, help='Bound parser snapshot, required with --run')
    parser.add_argument('--name', help='Output filename stem (default: input stem)')
    parser.add_argument('--output', type=Path, required=True, help='New output directory')
    args = parser.parse_args()
    if args.run and (not args.source or not args.snapshot):
        parser.error('--run requires --source and --snapshot')
    name = args.name or (args.input or args.source).stem
    if Path(name).name != name or name in {'.', '..'}:
        parser.error('--name must be a filename stem')
    args.output.mkdir(parents=True, exist_ok=True)
    outputs = [args.output / (name + suffix) for suffix in
               ['-usecases.svg', '-domain.svg', '-merm.html', '-merm.json', '-diagnostics.json']]
    if args.run:
        outputs.append(args.output / (name + '.req'))
    if any(p.exists() for p in outputs):
        parser.error('Output exists; choose a new directory or name to preserve previous results')
    req = export_req(args.run, args.source, args.snapshot, outputs[-1]) if args.run else args.input
    model = parse_req(req)
    model['name'] = name
    uc, uc_layout = render_svg(model, 'usecases')
    domain, domain_layout = render_svg(model, 'domain')
    model['layout'] = dict(usecases=uc_layout, domain=domain_layout)
    for path, content in [(outputs[0], uc), (outputs[1], domain), (outputs[2], render_html(model, uc, domain))]:
        path.write_text(content, encoding='utf-8')
    write_json(outputs[3], model)
    write_json(outputs[4], dict(inputHash=model['inputHash'], counts=model['counts'], diagnostics=model['diagnostics']))
    errors = sum(d['severity'] == 'error' for d in model['diagnostics'])
    print(str(outputs[2].resolve()))
    print('Counts:', model['counts'], '| errors:', errors)
    return 2 if errors else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, KeyError) as error:
        sys.exit('MERM: ' + str(error))
