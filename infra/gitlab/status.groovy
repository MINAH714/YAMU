def stateFor(String result) {
    return ['SUCCESS': 'success', 'FAILURE': 'failed', 'UNSTABLE': 'failed',
            'ABORTED': 'canceled', 'NOT_BUILT': 'skipped', 'RUNNING': 'running'][result] ?: 'failed'
}

def sendDevelop(String result) {
    if (env.JOB_NAME != 'yamu/ci' || env.GIT_BRANCH != 'origin/develop') return false
    try {
        withEnv(["YAMU_GITLAB_STATE=${stateFor(result)}"]) {
            withCredentials([file(credentialsId: 'yamu-gitlab-status-api', variable: 'YAMU_GITLAB_API_FILE')]) {
                sh 'set +x; python3 infra/gitlab/status.py develop-report'
            }
        }
        return true
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException interrupted) {
        throw interrupted
    } catch (Exception ignored) {
        echo 'WARNING: GitLab status delivery failed; Jenkins build result is unchanged.'
        return false
    }
}

return this
