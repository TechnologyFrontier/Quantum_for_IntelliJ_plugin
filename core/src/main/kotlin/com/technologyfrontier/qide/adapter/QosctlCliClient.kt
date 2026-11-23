package com.technologyfrontier.qide.adapter

import com.technologyfrontier.qide.application.Cancellation
import com.technologyfrontier.qide.application.QosctlClient
import com.technologyfrontier.qide.domain.QosFailure
import com.technologyfrontier.qide.domain.QosOutcome
import com.technologyfrontier.qide.domain.RunRequest
import com.technologyfrontier.qide.domain.RunResult
import com.technologyfrontier.qide.domain.ServiceState
import com.technologyfrontier.qide.domain.Validated
import java.nio.file.Files
import java.nio.file.Path

/**
 * 管理された絶対パスの qosctl を子プロセスとして起動する、唯一の CLI 起動元（機能設計書 6章・7章）。
 * ソケットへの直接接続、シェル経由の実行、qos-admin の起動は行わない。
 */
class QosctlCliClient(
    private val executable: Path,
    private val workingDirectory: Path?,
    launcher: ProcessLauncher = JdkProcessLauncher(),
    private val limits: ExecutionLimits = ExecutionLimits(),
) : QosctlClient {

    private val executor = ProcessExecutor(launcher, limits)

    override fun validate(circuit: Path, cancellation: Cancellation): QosOutcome<Validated> {
        precheck(circuit)?.let { return it }
        return QosctlResponses.validate(invoke(QosctlArguments.validate(circuit), limits.validateTimeout, cancellation))
    }

    override fun run(request: RunRequest, cancellation: Cancellation): QosOutcome<RunResult> {
        precheck(request.circuit)?.let { return it }
        return QosctlResponses.run(invoke(QosctlArguments.run(request), limits.runTimeout, cancellation), request)
    }

    override fun status(cancellation: Cancellation): QosOutcome<ServiceState> {
        precheck(null)?.let { return it }
        return QosctlResponses.status(invoke(QosctlArguments.status(), limits.statusTimeout, cancellation))
    }

    private fun invoke(arguments: List<String>, timeout: java.time.Duration, cancellation: Cancellation) =
        executor.execute(executable, arguments, workingDirectory, timeout, cancellation)

    private fun precheck(circuit: Path?): QosOutcome.Failure? {
        if (!executable.isAbsolute) return fail("qosctl のパスが絶対パスではありません: $executable")
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            return fail("qosctl が見つからないか実行できません: $executable")
        }
        if (circuit != null) {
            if (!circuit.isAbsolute) return fail("回路パスが絶対パスではありません: $circuit")
            if (!Files.isRegularFile(circuit)) return fail("回路ファイルが見つかりません: $circuit")
        }
        return null
    }

    private fun fail(reason: String) = QosOutcome.Failure(QosFailure.IntegrationFailure(reason))
}
