import java.time.LocalDate
import org.springframework.boot.gradle.tasks.run.BootRun
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestResult

plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    // 使用 BOM(platform) 管理版本，不需要 dependency-management 插件
    // id("io.spring.dependency-management") version "1.1.6"
}

group = "com.getjobs"
version = "2.0.1"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories {
    mavenCentral()
    // 国内镜像可选：
    // maven { url = uri("https://maven.aliyun.com/repository/public") }
}

dependencies {
    // 用 Spring Boot 官方 BOM 管理版本（推荐）
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))

    // 受 BOM 管理的依赖（不写版本）
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("org.apache.httpcomponents.client5:httpclient5-fluent")

    // 不在 BOM 中的依赖（写版本）
    // 1.62.0 是为了和 patchright-core 1.62.1 对齐（见 installPatchrightDriver 任务）。
    // 不要降到 1.51：patchright-core 1.51.3 的 locator.count() 是坏的（_progress is not defined）
    implementation("com.microsoft.playwright:playwright:1.62.0")
    implementation("com.baomidou:mybatis-plus-spring-boot3-starter:3.5.9")
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")
    // 代码生成器（MyBatis-Plus Generator + Freemarker 模板）
    implementation("com.baomidou:mybatis-plus-generator:3.5.9")
    implementation("org.freemarker:freemarker:2.3.32")
    implementation("org.json:json:20231013")
    implementation("io.github.cdimascio:dotenv-java:2.2.0")
    implementation("jakarta.annotation:jakarta.annotation-api:2.1.1")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Lombok：两行不是重复声明，两个配置各管一段，缺一不可
    //   compileOnly         -> 只在编译期可见，不进运行时 classpath、不打进 jar
    //   annotationProcessor -> 注解处理器，@Data/@Slf4j 这些代码是它生成的
    // 少了 compileOnly，src/main 里的 import lombok.* 会全部编译失败；
    // 少了 annotationProcessor，编译能过但生成的方法不存在，留到运行时才炸。
    // 版本号提成变量，避免两行各写一遍、日后升级漏改。
    val lombok = "org.projectlombok:lombok:1.18.42"
    compileOnly(lombok)
    annotationProcessor(lombok)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // 普通 test 不隐式下载浏览器
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    resolveNodePath()?.let { environment("PLAYWRIGHT_NODEJS_PATH", it) }
}

/** 检查本地 Playwright / Chrome 浏览器内核是否存在，不自动隐式下载 */
fun findPlaywrightBrowser(): File? {
    val custom = System.getenv("BOSS_TEST_CHROME")
    if (!custom.isNullOrBlank()) {
        val f = File(custom)
        if (f.isFile) return f
    }
    val customBrowsersPath = System.getenv("PLAYWRIGHT_BROWSERS_PATH")
    val candidateDirs = mutableListOf<File>()
    if (!customBrowsersPath.isNullOrBlank()) {
        candidateDirs.add(File(customBrowsersPath))
    }
    System.getenv("LOCALAPPDATA")?.let { candidateDirs.add(File(it, "ms-playwright")) }
    System.getenv("USERPROFILE")?.let { candidateDirs.add(File(it, "AppData/Local/ms-playwright")) }
    candidateDirs.add(File(System.getProperty("user.home"), ".cache/ms-playwright"))
    candidateDirs.add(File(System.getProperty("user.home"), "Library/Caches/ms-playwright"))

    for (dir in candidateDirs) {
        if (!dir.isDirectory) continue
        val found = dir.walkTopDown().firstOrNull { file ->
            file.isFile && (
                file.name.equals("chrome-headless-shell.exe", ignoreCase = true) ||
                file.name == "chrome-headless-shell" ||
                file.name.equals("chrome.exe", ignoreCase = true) ||
                file.name == "chrome"
            )
        }
        if (found != null) return found
    }
    return null
}

fun assertTestSuiteIntegrity(testCount: Long, skippedTestCount: Long, taskName: String) {
    if (testCount == 0L) {
        throw GradleException("$taskName 未扫描到任何测试用例！")
    }
    if (skippedTestCount > 0L) {
        throw GradleException("$taskName 中有 $skippedTestCount 项测试被跳过！完整验收要求所有测试必须执行，不得静默跳过。")
    }
}

val domTest = tasks.register<Test>("domTest") {
    group = "verification"
    description = "执行 BOSS 聊天 DOM 自动化测试（显式入口：前置检查浏览器内核，不隐式下载，完整执行且不得静默跳过）"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    include("**/*DomTest.class", "**/*DomTest*.class")

    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }

    environment("BOSS_CHAT_DOM_TEST", "true")
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    findPlaywrightBrowser()?.let { environment("BOSS_TEST_CHROME", it.absolutePath) }
    resolveNodePath()?.let { environment("PLAYWRIGHT_NODEJS_PATH", it) }

    doFirst {
        val browser = findPlaywrightBrowser()
        if (browser == null) {
            throw GradleException(
                """
                |
                |=================================================================================
                | [DOM 验证失败: 缺少浏览器内核]
                | 未在系统缓存中检测到可用的 Playwright 浏览器内核 (chrome-headless-shell 或 chromium)。
                | 为保证确定性与离线环境安全，普通 test 与 DOM 测试均严格禁止隐式下载浏览器！
                |
                | 请先执行以下命令显式安装所需浏览器内核：
                |   ./gradlew installPatchrightDriver
                |   node build/patchright-driver/package/cli.js install chromium-headless-shell
                |
                | （若已安装系统 Chrome，也可设置环境变量 BOSS_TEST_CHROME 指向 chrome.exe）
                |=================================================================================
                """.trimMargin()
            )
        }
        logger.lifecycle("DOM 测试检测到可用浏览器: ${browser.absolutePath}")
    }

    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                logger.lifecycle("DOM 验证结果汇总: 总计 ${result.testCount} 项, 成功 ${result.successfulTestCount} 项, 失败 ${result.failedTestCount} 项, 跳过 ${result.skippedTestCount} 项")
                assertTestSuiteIntegrity(result.testCount, result.skippedTestCount, "DOM 验证任务")
            }
        }
    })
}

tasks.register("testDom") {
    group = "verification"
    description = "别名任务：执行 domTest"
    dependsOn(domTest)
}

val fullTest = tasks.register<Test>("fullTest") {
    group = "verification"
    description = "执行全量测试（包含单元测试与 DOM 测试，要求前置浏览器到位，DOM 测试不得跳过）"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()

    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }

    environment("BOSS_CHAT_DOM_TEST", "true")
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    findPlaywrightBrowser()?.let { environment("BOSS_TEST_CHROME", it.absolutePath) }
    resolveNodePath()?.let { environment("PLAYWRIGHT_NODEJS_PATH", it) }

    doFirst {
        val browser = findPlaywrightBrowser()
        if (browser == null) {
            throw GradleException(
                """
                |
                |=================================================================================
                | [全量测试失败: 缺少浏览器内核]
                | 未在系统缓存中检测到可用的 Playwright 浏览器内核 (chrome-headless-shell 或 chromium)。
                | 为保证确定性与离线环境安全，测试严格禁止隐式下载浏览器！
                |
                | 请先执行以下命令显式安装所需浏览器内核：
                |   ./gradlew installPatchrightDriver
                |   node build/patchright-driver/package/cli.js install chromium-headless-shell
                |
                | （若已安装系统 Chrome，也可配置环境变量 BOSS_TEST_CHROME 指向 chrome.exe）
                |=================================================================================
                """.trimMargin()
            )
        }
        logger.lifecycle("全量测试检测到可用浏览器: ${browser.absolutePath}")
    }

    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}
        override fun beforeTest(testDescriptor: TestDescriptor) {}
        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                logger.lifecycle("全量测试结果汇总: 总计 ${result.testCount} 项, 成功 ${result.successfulTestCount} 项, 失败 ${result.failedTestCount} 项, 跳过 ${result.skippedTestCount} 项")
                assertTestSuiteIntegrity(result.testCount, result.skippedTestCount, "全量测试")
            }
        }
    })
}

tasks.register("verifyGuard") {
    group = "verification"
    description = "反向验证：验证守护共享函数 assertTestSuiteIntegrity 在零用例与跳过时能够中断构建并抛出异常"
    doLast {
        var zeroCountCaught = false
        try {
            assertTestSuiteIntegrity(0L, 0L, "全量测试")
        } catch (e: GradleException) {
            if (e.message?.contains("未扫描到任何测试用例") == true) {
                zeroCountCaught = true
            }
        }
        if (!zeroCountCaught) throw GradleException("零用例守卫反向验证失败！")

        var skippedCaught = false
        try {
            assertTestSuiteIntegrity(10L, 1L, "全量测试")
        } catch (e: GradleException) {
            if (e.message?.contains("项测试被跳过") == true) {
                skippedCaught = true
            }
        }
        if (!skippedCaught) throw GradleException("跳过守卫反向验证失败！")

        logger.lifecycle(">> [守护共享函数反向验证通过] 共享函数 assertTestSuiteIntegrity 对零用例与跳过均能抛出 GradleException 并中断构建。")
    }
}

// 显示已过时 API 的详细告警，便于定位并修复
tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("-Xlint:deprecation"))
}

// 让 bootJar 里带上 build-info（可在 Actuator /info 里看到）
springBoot {
    buildInfo()
}

// ---------------------------------------------------------------------------
// Patchright driver
//
// playwright-java 本身只是个壳，真正干活的是它拉起的 Node driver 进程。
// Patchright 是打过反检测补丁的 Playwright（去掉 Runtime.enable / Console.enable 泄漏、
// 加 --disable-blink-features=AutomationControlled 等），官方只出 Python/Node/.NET，
// 没有 Java 版；但 Java 客户端支持 -Dplaywright.cli.dir 指向一个预装的 driver 目录，
// 所以可以让 Java 客户端去驱动 patchright 的 Node driver。
//
// 目录结构要求（见 com.microsoft.playwright.impl.driver.Driver）：
//     <driverDir>/package/cli.js   <- patchright-core 包内容
// node 可执行文件用本机的，通过 PLAYWRIGHT_NODEJS_PATH 传进去。
// ---------------------------------------------------------------------------
val patchrightVersion = "1.62.1"
val patchrightDriverDir = layout.buildDirectory.dir("patchright-driver")

val installPatchrightDriver = tasks.register("installPatchrightDriver") {
    group = "playwright"
    description = "下载 patchright-core 并装配成 playwright-java 能识别的 driver 目录"

    val outDir = patchrightDriverDir
    outputs.dir(outDir)

    doLast {
        val driverDir = outDir.get().asFile
        val packageDir = File(driverDir, "package")
        val marker = File(driverDir, ".version")

        if (marker.isFile && marker.readText().trim() == patchrightVersion && File(packageDir, "cli.js").isFile) {
            logger.lifecycle("patchright-core $patchrightVersion 已就绪: $packageDir")
            return@doLast
        }

        driverDir.deleteRecursively()
        driverDir.mkdirs()

        logger.lifecycle("正在下载 patchright-core@$patchrightVersion ...")
        val npm = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"
        providers.exec {
            commandLine(npm, "install", "patchright-core@$patchrightVersion",
                    "--prefix", driverDir.absolutePath, "--no-audit", "--no-fund", "--loglevel=error")
        }.result.get().assertNormalExitValue()

        val installed = File(driverDir, "node_modules/patchright-core")
        if (!File(installed, "cli.js").isFile) {
            throw GradleException("patchright-core 安装后没找到 cli.js: $installed")
        }
        // Driver 期望的是 <driverDir>/package/cli.js，把包整个搬过去
        copy {
            from(installed)
            into(packageDir)
        }
        marker.writeText(patchrightVersion)
        logger.lifecycle("patchright driver 已装配: $packageDir")
    }
}

val isWindowsOs = System.getProperty("os.name").lowercase().contains("windows")

/** 在 PATH 里找可执行文件，Windows 上要连 .cmd / .exe 一起找 */
fun findOnPath(name: String): File? {
    val candidates = if (isWindowsOs) listOf("$name.cmd", "$name.exe", "$name.bat", name) else listOf(name)
    val dirs = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
    for (dir in dirs) {
        if (dir.isBlank()) continue
        for (candidate in candidates) {
            val file = File(dir.trim(), candidate)
            if (file.isFile) return file
        }
    }
    return null
}

/** 本机 node 可执行文件路径，交给 Driver 用（PLAYWRIGHT_NODEJS_PATH） */
fun resolveNodePath(): String? = findOnPath("node")?.absolutePath

// ---------------------------------------------------------------------------
// 前端（管理页面）构建
//
// 管理页面是 front/ 这个 Next.js 工程静态导出的产物，落在 src/main/resources/dist。
// 那个目录在 .gitignore 里，clone 下来没有；不构建的话启动日志会打
// "未找到可用的管理页面，跳过自动打开浏览器"，浏览器也不会弹出来。
//
// 注意不能用 `pnpm run build:prod`：pnpm 跑脚本前会做依赖检查，而本项目的
// sharp / unrs-resolver 构建脚本被 pnpm 默认忽略，检查会以退出码 1 结束，
// 把真正的构建挡在外面。所以这里直接用 node 调 next 和复制脚本。
// ---------------------------------------------------------------------------
val frontendDir = layout.projectDirectory.dir("front").asFile
val frontendDistDir = layout.projectDirectory.dir("src/main/resources/dist").asFile

val buildFrontend = tasks.register("buildFrontend") {
    group = "frontend"
    description = "构建 Next.js 管理页面并复制到 src/main/resources/dist（已存在则跳过，-PrebuildFrontend 强制重建）"

    val forceRebuild = providers.gradleProperty("rebuildFrontend").isPresent

    onlyIf {
        when {
            !frontendDir.isDirectory -> {
                logger.lifecycle("没有 front 目录，跳过前端构建")
                false
            }
            !forceRebuild && File(frontendDistDir, "index.html").isFile -> {
                logger.lifecycle("管理页面已存在，跳过前端构建（需要重建请加 -PrebuildFrontend）")
                false
            }
            else -> true
        }
    }

    doLast {
        val node = findOnPath("node")
        if (node == null) {
            logger.warn("PATH 里没找到 node，跳过前端构建；管理页面将不可用。" +
                    "装好 Node.js 后执行: gradlew buildFrontend -PrebuildFrontend")
            return@doLast
        }

        // 依赖没装（或只剩个空目录）时先补上
        if (!File(frontendDir, "node_modules/next").isDirectory) {
            val corepack = findOnPath("corepack")
            if (corepack == null) {
                logger.warn("前端依赖未安装，且 PATH 里没有 corepack，跳过前端构建。" +
                        "可手动执行: cd front && pnpm install")
                return@doLast
            }
            logger.lifecycle("安装前端依赖（corepack pnpm install）...")
            // pnpm 会因为"忽略了 sharp/unrs-resolver 的构建脚本"以 1 退出，
            // 这不是真失败，所以不校验退出码，改为下面检查 node_modules 是否到位
            providers.exec {
                workingDir = frontendDir
                commandLine(corepack.absolutePath, "pnpm", "install")
                isIgnoreExitValue = true
            }.result.get()

            if (!File(frontendDir, "node_modules/next").isDirectory) {
                logger.warn("前端依赖安装后仍找不到 next，跳过前端构建")
                return@doLast
            }
        }

        logger.lifecycle("构建管理页面（next build）...")
        providers.exec {
            workingDir = frontendDir
            commandLine(node.absolutePath, "node_modules/next/dist/bin/next", "build")
        }.result.get().assertNormalExitValue()

        logger.lifecycle("复制构建产物到 src/main/resources/dist ...")
        providers.exec {
            workingDir = frontendDir
            commandLine(node.absolutePath, "scripts/copy-dist.mjs")
        }.result.get().assertNormalExitValue()

        logger.lifecycle("管理页面已就绪: $frontendDistDir")
    }
}

// 打包时也要把管理页面带进 jar：dist 在 src/main/resources 下，由 processResources 收集
tasks.named<ProcessResources>("processResources") {
    dependsOn(buildFrontend)
    // 旧版要求用户把个人简历放进 src/main/resources；即使被 Git 忽略，它仍会被打进 JAR。
    // 新版改为运行时上传，因此明确排除旧文件，避免个人简历随安装包泄露。
    exclude("resume", "resume.*")
}

// 正确地配置 BootRun（注意类型是 BootRun）
tasks.named<BootRun>("bootRun") {
    dependsOn(installPatchrightDriver, buildFrontend)

    systemProperty("file.encoding", "UTF-8")
    systemProperty("sun.stdout.encoding", "UTF-8")
    systemProperty("sun.stderr.encoding", "UTF-8")
    // 示例：把当天日期传给日志或应用
    systemProperty("LOG_DATE", LocalDate.now().toString())
    // 可选：对齐端口
    // systemProperty("server.port", "8888")

    // 让 playwright-java 用 patchright 的 driver，而不是自带的 driver-bundle
    systemProperty("playwright.cli.dir", patchrightDriverDir.get().asFile.absolutePath)
    resolveNodePath()?.let { environment("PLAYWRIGHT_NODEJS_PATH", it) }

    // 透传 boss. 与 BOSS_ 开头的系统属性给应用 JVM（如 -Dboss.readOnlyCheckMode=true）
    System.getProperties().forEach { key, value ->
        val k = key.toString()
        if (k.startsWith("boss.") || k.startsWith("BOSS_")) {
            systemProperty(k, value.toString())
        }
    }
}
