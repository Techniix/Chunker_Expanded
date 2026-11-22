"""Derive default Bedrock entity component groups (adult / baby / tamed / chested / variants) from Mojang's behaviour
pack entity files (https://github.com/Mojang/bedrock-samples, behavior_pack/entities).

Usage: pip install json5
       python3 tools/gen_bedrock_entity_definitions.py <bedrock-samples>/behavior_pack/entities \
           cli/src/main/resources/bedrock/entity_definitions.json
"""
import json, json5, glob, re, sys, os

def load(path):
    text = open(path, encoding='utf-8').read()
    text = re.sub(r'^\s*//.*$', '', text, flags=re.M)
    return json5.loads(text)

def simulate(entity, event_name, baby, groups, depth=0):
    events = entity.get('events') or {}
    event = events.get(event_name)
    if event is None or depth > 12:
        return
    apply(entity, event, baby, groups, depth)

WORLD_TESTS = ('biome', 'weather', 'is_snow', 'time', 'moon', 'altitude', 'light', 'underwater', 'in_water',
               'is_underground', 'surface', 'difficulty', 'has_target', 'is_family', 'has_equipment', 'is_daytime')

def filter_passes(filters):
    # Filters about the entity's own state at spawn ("doesn't have component X yet") pass, world dependent ones don't
    text = json.dumps(filters)
    return not any(test in text for test in WORLD_TESTS)

def mentions_baby(node):
    return 'baby' in json.dumps(node).lower()

def apply(entity, node, baby, groups, depth):
    if not isinstance(node, dict):
        return
    if 'filters' in node and not filter_passes(node['filters']):
        return  # Conditional on the world (biome, weather, etc.), skip
    for g in node.get('add', {}).get('component_groups', []):
        if g not in groups:
            groups.append(g)
    for g in node.get('remove', {}).get('component_groups', []):
        if g in groups:
            groups.remove(g)
    if 'trigger' in node:
        trig = node['trigger']
        name = trig if isinstance(trig, str) else trig.get('event')
        if name:
            simulate(entity, name, baby, groups, depth + 1)
    if 'sequence' in node:
        for child in node['sequence']:
            apply(entity, child, baby, groups, depth)
    if 'first_valid' in node:
        for child in node['first_valid']:
            if 'filters' not in child:
                apply(entity, child, baby, groups, depth)
                break
    if 'randomize' in node:
        options = node['randomize']
        babies = [o for o in options if mentions_baby(o)]
        adults = [o for o in options if not mentions_baby(o)]
        pool = (babies if baby and babies else adults if adults else options)
        best = max(pool, key=lambda o: o.get('weight', 1) if isinstance(o, dict) else 0)
        apply(entity, best, baby, groups, depth)

def main(src, out):
    result = {}
    for path in sorted(glob.glob(os.path.join(src, '*.json'))):
        try:
            data = load(path)['minecraft:entity']
        except Exception as e:
            print('skip', path, e, file=sys.stderr)
            continue
        identifier = data['description']['identifier']
        entry = {}
        for mode in ('adult', 'baby'):
            groups = []
            simulate(data, 'minecraft:entity_spawned', mode == 'baby', groups)
            if mode == 'baby' and not any('baby' in g for g in groups):
                groups = None  # No baby form
            entry[mode] = groups
        events = data.get('events') or {}
        tame_event = next((e for e in ('minecraft:on_tame', 'minecraft:on_tamed', 'minecraft:become_tame') if e in events), None)
        if tame_event and entry['adult'] is not None:
            tamed = list(entry['adult'])
            simulate(data, tame_event, False, tamed)
            entry['tamed'] = tamed
        chest_event = next((e for e in ('minecraft:on_chest',) if e in events), None)
        if chest_event:
            chested = list(entry.get('tamed') or entry['adult'])
            simulate(data, chest_event, False, chested)
            entry['chested'] = chested
        variants = {}
        become = [e for e in events if e.startswith('minecraft:become_')]
        if become and entry['adult'] is not None:
            # Groups any variant event adds are removed from the adult base first, so variants don't stack
            variant_groups = set()
            for event_name in become:
                added = []
                simulate(data, event_name, False, added)
                variant_groups.update(added)
            base = [g for g in entry['adult'] if g not in variant_groups]
            for event_name in become:
                groups = list(base)
                simulate(data, event_name, False, groups)
                variants[event_name[len('minecraft:become_'):]] = groups
        # Professions without a "become" event (e.g. nitwit) are modelled on the unemployed variant
        if 'unskilled' in variants:
            for group_name, group in (data.get('component_groups') or {}).items():
                family = (group or {}).get('minecraft:type_family', {}).get('family', []) if isinstance(group, dict) else []
                if group_name not in variants and group_name in family and 'minecraft:variant' in group:
                    variants[group_name] = [group_name if g == 'unskilled' else g for g in variants['unskilled']]
        if variants:
            entry['variants'] = variants
        # Groups which only exist to set a variant / mark variant value
        for component, key in (('minecraft:variant', 'variant_groups'), ('minecraft:mark_variant', 'mark_variant_groups')):
            mapping = {}
            for group_name, group in (data.get('component_groups') or {}).items():
                if isinstance(group, dict) and component in group and isinstance(group[component], dict):
                    value = group[component].get('value')
                    if isinstance(value, int) and str(value) not in mapping:
                        mapping[str(value)] = group_name
            if len(mapping) > 1:
                entry[key] = mapping
        result[identifier] = {k: v for k, v in entry.items() if v}
    with open(out, 'w') as f:
        json.dump(result, f, indent=1, sort_keys=True)
    print('entities', len(result))

main(sys.argv[1], sys.argv[2])
