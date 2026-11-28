import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))
const web = fileURLToPath(new URL('../web/', import.meta.url))
const windows = process.platform === 'win32'

function run(command, args, cwd) {
    const invocation = [command, ...args].join(' ')
    console.log(`\n> ${invocation}`)
    // Windows installs Maven and npm as .cmd launchers. All command arguments below are literals.
    const result = spawnSync(windows ? invocation : command, windows ? [] : args, {
        cwd,
        shell: windows,
        stdio: 'inherit'
    })
    if (result.error) console.error(result.error.message)
    if (result.status !== 0) process.exit(result.status ?? 1)
}

run('mvn', ['-B', '-ntp', '-f', 'rag/pom.xml', '-Pfast-ci', 'test'], root)
run('npm', ['test'], web)
run('npm', ['run', 'build'], web)
