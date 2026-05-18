import test from "node:test";
import assert from "node:assert/strict";
import {
  defaultDistRoot,
  javaMockRunTurnBackendArgs,
  javaRealJarBackendArgs,
  javaRealRunBackendArgs,
  minicodeJarPath,
  resolveJavaCommand
} from "../src/java-backend.js";

test("builds explicit Java mock runTurn backend args instead of fixed scripted backend", () => {
  const args = javaMockRunTurnBackendArgs({
    workspaceCwd: "E:\\work dir",
    maxSteps: 4
  });

  assert.deepEqual(
    args.slice(0, 4),
    ["-q", "-DskipTests", "compile", "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"]
  );
  assert.ok(args.includes("\"-Dexec.mainClass=minicode.app.MiniCodeApp\""));
  const execArgs = args.find((arg: string) => arg.startsWith("-Dexec.args="));
  assert.ok(execArgs);
  assert.match(execArgs, /--ui-stdio-mock-run/);
  assert.match(execArgs, /--cwd/);
  assert.match(execArgs, /--max-steps 4/);
  assert.doesNotMatch(execArgs, /--ui-stdio-mock(\s|$)/);
});

test("builds explicit Java real backend args only when requested", () => {
  const args = javaRealRunBackendArgs({
    workspaceCwd: "E:\\work dir",
    maxSteps: 8
  });

  assert.deepEqual(
    args.slice(0, 4),
    ["-q", "-DskipTests", "compile", "org.codehaus.mojo:exec-maven-plugin:3.5.0:java"]
  );
  assert.ok(args.includes("\"-Dexec.mainClass=minicode.app.MiniCodeApp\""));
  const execArgs = args.find((arg: string) => arg.startsWith("-Dexec.args="));
  assert.ok(execArgs);
  assert.match(execArgs, /--ui-stdio-run/);
  assert.match(execArgs, /--cwd/);
  assert.match(execArgs, /--max-steps 8/);
  assert.doesNotMatch(execArgs, /--ui-stdio-mock-run/);
});

test("builds packaged Java real backend args against sibling dist jar", () => {
  const args = javaRealJarBackendArgs({
    distRoot: "E:\\release\\minicode",
    workspaceCwd: "E:\\work dir",
    maxSteps: 9
  });

  assert.deepEqual(args, [
    "-jar",
    "E:\\release\\minicode\\lib\\minicode.jar",
    "--ui-stdio-run",
    "--cwd",
    "E:\\work dir",
    "--max-steps",
    "9"
  ]);
});

test("resolves packaged dist root next to compiled ts-cli output", () => {
  const distRoot = defaultDistRoot("file:///E:/MiniCode-Java/target/dist/minicode/ts-cli/src/main.js");
  assert.equal(distRoot, "E:\\MiniCode-Java\\target\\dist\\minicode");
});

test("resolves minicode jar inside lib directory", () => {
  assert.equal(
    minicodeJarPath("E:\\release\\minicode"),
    "E:\\release\\minicode\\lib\\minicode.jar"
  );
});

test("prefers JAVA_HOME21 over JAVA_HOME and PATH", () => {
  const resolution = resolveJavaCommand({
    JAVA_HOME21: "E:\\soft\\java\\jdk21",
    JAVA_HOME: "E:\\soft\\java\\jdk17"
  }, (candidate) => candidate === "E:\\soft\\java\\jdk21\\bin\\java.exe");

  assert.equal(resolution.source, "JAVA_HOME21");
  assert.equal(resolution.command, "E:\\soft\\java\\jdk21\\bin\\java.exe");
});

test("falls back to JAVA_HOME when JAVA_HOME21 is absent", () => {
  const resolution = resolveJavaCommand({
    JAVA_HOME: "E:\\soft\\java\\jdk21"
  }, (candidate) => candidate === "E:\\soft\\java\\jdk21\\bin\\java.exe");

  assert.equal(resolution.source, "JAVA_HOME");
  assert.equal(resolution.command, "E:\\soft\\java\\jdk21\\bin\\java.exe");
});

test("falls back to JAVA_HOME when JAVA_HOME21 is configured but missing java", () => {
  const resolution = resolveJavaCommand({
    JAVA_HOME21: "E:\\soft\\java\\missing",
    JAVA_HOME: "E:\\soft\\java\\jdk21"
  }, (candidate) => candidate === "E:\\soft\\java\\jdk21\\bin\\java.exe");

  assert.equal(resolution.source, "JAVA_HOME");
  assert.equal(resolution.command, "E:\\soft\\java\\jdk21\\bin\\java.exe");
});

test("falls back to plain java when no explicit home is configured", () => {
  const resolution = resolveJavaCommand({}, () => false);

  assert.equal(resolution.source, "PATH");
  assert.equal(resolution.command, "java");
});
