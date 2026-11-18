import { randomInt } from 'node:crypto';
import { spawnSync } from 'node:child_process';

const names = ['default', 'command-egress', 'command-egress-secondary'];
function network(args) {
    const result = spawnSync('docker', ['network', ...args], {encoding:'utf8', timeout:15000});
    if (result.error) throw result.error;
    if (result.status !== 0) throw new Error(result.stderr.trim());
    return result.stdout.trim();
}

// Docker arbitrates overlapping CIDRs atomically, including collisions with other tracer processes.
export function reserveTracerNetworks(project) {
    for (let attempt=0; attempt<8; attempt++) {
        const octet=randomInt(256), ids=[];
        const release=() => { for (const id of ids.toReversed()) network(['rm',id]); };
        try {
            for (const [i,name] of names.entries()) ids.push(network(['create','--driver','bridge', ...(i ? ['--internal'] : []),
                '--subnet',`10.${201+i}.${octet}.0/24`, '--ip-range',`10.${201+i}.${octet}.0/25`, '--label',`codecraft.tracer=${project}`, `${project}_${name}`]));
            return {octet,release};
        } catch (error) {
            release();
            if (!/pool overlaps/i.test(error.message) || attempt===7) throw error;
        }
    }
}

export function fixtureCompose(template, project, octet) {
    const external = names.map(name => `  ${name}:\n    external: true\n    name: ${project}_${name}`).join('\n');
    return template.replaceAll('\r\n','\n').replace(/^networks:\n[\s\S]*?^volumes:/m,`networks:\n${external}\nvolumes:`)
        .replace(/\b172\.(29|30|31)\.0\.(2|3)\b/g, (_,segment,host) => `10.${Number(segment)+172}.${octet}.${Number(host)+248}`);
}
