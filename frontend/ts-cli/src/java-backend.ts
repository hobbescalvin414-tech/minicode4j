import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export type JavaBackendHandle = {
  process: ChildProcessWithoutNullStreams;
  command: string;
  args: string[];
  cwd: string;
};

export type JavaCommandResolution = {
  command: string;
  source: "JAVA_HOME21" | "JAVA_HOME" | "PATH";
};

export function startJavaMockBackend(options: {
  repoRoot: string;
  workspaceCwd: string;
  mavenCommand?: string;
}): JavaBackendHandle {
  const command = options.mavenCommand ?? process.env.MINICODE_MVN_CMD ?? "mvn";
  const args = [
    "-q",
    "-DskipTests",
    "compile",
    "org.codehaus.mojo:exec-maven-plugin:3.5.0:java",
    "\"-Dexec.mainClass=minicode.app.MiniCodeApp\"",
    `-Dexec.args="--ui-stdio-mock --cwd ${quoteExecArg(options.workspaceCwd)}"`
  ];
  const child = spawn(command, args, {
    cwd: options.repoRoot,
    stdio: ["pipe", "pipe", "pipe"],
    shell: process.platform === "win32"
  });
  return { process: child, command, args, cwd: options.repoRoot };
}

export function startJavaMockRunTurnBackend(options: {
  repoRoot: string;
  workspaceCwd: string;
  mavenCommand?: string;
  maxSteps?: number;
}): JavaBackendHandle {
  const command = options.mavenCommand ?? process.env.MINICODE_MVN_CMD ?? "mvn";
  const args = javaMockRunTurnBackendArgs({
    workspaceCwd: options.workspaceCwd,
    maxSteps: options.maxSteps
  });
  const child = spawn(command, args, {
    cwd: options.repoRoot,
    stdio: ["pipe", "pipe", "pipe"],
    shell: process.platform === "win32"
  });
  return { process: child, command, args, cwd: options.repoRoot };
}

export function startJavaRealBackend(options: {
  repoRoot: string;
  workspaceCwd: string;
  mavenCommand?: string;
  maxSteps?: number;
  distRoot?: string;
}): JavaBackendHandle {
  const distRoot = options.distRoot ?? process.env.MINICODE4J_DIST_ROOT;
  if (distRoot != null && existsSync(minicodeJarPath(distRoot))) {
    const resolution = resolveJavaCommand(process.env);
    const args = javaRealJarBackendArgs({
      distRoot,
      workspaceCwd: options.workspaceCwd,
      maxSteps: options.maxSteps
    });
    const child = spawn(resolution.command, args, {
      cwd: options.workspaceCwd,
      stdio: ["pipe", "pipe", "pipe"],
      shell: false
    });
    return { process: child, command: resolution.command, args, cwd: options.workspaceCwd };
  }
  const command = options.mavenCommand ?? process.env.MINICODE_MVN_CMD ?? "mvn";
  const args = javaRealRunBackendArgs({
    workspaceCwd: options.workspaceCwd,
    maxSteps: options.maxSteps
  });
  const child = spawn(command, args, {
    cwd: options.repoRoot,
    stdio: ["pipe", "pipe", "pipe"],
    shell: process.platform === "win32"
  });
  return { process: child, command, args, cwd: options.repoRoot };
}

export function javaMockRunTurnBackendArgs(options: { workspaceCwd: string; maxSteps?: number }): string[] {
  const maxSteps = options.maxSteps ?? 4;
  return [
    "-q",
    "-DskipTests",
    "compile",
    "org.codehaus.mojo:exec-maven-plugin:3.5.0:java",
    "\"-Dexec.mainClass=minicode.app.MiniCodeApp\"",
    `-Dexec.args="--ui-stdio-mock-run --cwd ${quoteExecArg(options.workspaceCwd)} --max-steps ${maxSteps}"`
  ];
}

export function javaRealRunBackendArgs(options: { workspaceCwd: string; maxSteps?: number }): string[] {
  const maxSteps = options.maxSteps ?? 32;
  return [
    "-q",
    "-DskipTests",
    "compile",
    "org.codehaus.mojo:exec-maven-plugin:3.5.0:java",
    "\"-Dexec.mainClass=minicode.app.MiniCodeApp\"",
    `-Dexec.args="--ui-stdio-run --cwd ${quoteExecArg(options.workspaceCwd)} --max-steps ${maxSteps}"`
  ];
}

export function javaRealJarBackendArgs(options: {
  distRoot: string;
  workspaceCwd: string;
  maxSteps?: number;
}): string[] {
  const maxSteps = options.maxSteps ?? 32;
  return [
    "-jar",
    minicodeJarPath(options.distRoot),
    "--ui-stdio-run",
    "--cwd",
    options.workspaceCwd,
    "--max-steps",
    String(maxSteps)
  ];
}

export function defaultRepoRoot(compiledMainUrl: string): string {
  const mainPath = fileURLToPath(compiledMainUrl);
  return path.resolve(path.dirname(mainPath), "../../../..");
}

export function defaultDistRoot(compiledMainUrl: string): string {
  const mainPath = fileURLToPath(compiledMainUrl);
  const mainDir = path.dirname(mainPath);
  if (path.basename(mainDir) === "src" && path.basename(path.dirname(mainDir)) === "ts-cli") {
    return path.resolve(mainDir, "../..");
  }
  return path.resolve(mainDir, "..");
}

export function minicodeJarPath(distRoot: string): string {
  return path.resolve(distRoot, "lib", "minicode.jar");
}

export function resolveJavaCommand(
  env: NodeJS.ProcessEnv,
  fileExists: (candidate: string) => boolean = existsSync
): JavaCommandResolution {
  const javaHome21 = env.JAVA_HOME21;
  if (javaHome21 != null && javaHome21.length > 0) {
    const command = path.join(javaHome21, "bin", process.platform === "win32" ? "java.exe" : "java");
    if (fileExists(command)) {
      return {
        command,
        source: "JAVA_HOME21"
      };
    }
  }
  const javaHome = env.JAVA_HOME;
  if (javaHome != null && javaHome.length > 0) {
    const command = path.join(javaHome, "bin", process.platform === "win32" ? "java.exe" : "java");
    if (fileExists(command)) {
      return {
        command,
        source: "JAVA_HOME"
      };
    }
  }
  return { command: "java", source: "PATH" };
}

function quoteExecArg(value: string): string {
  return value.includes(" ") ? `\\"${value.replaceAll("\"", "\\\"")}\\"` : value;
}
