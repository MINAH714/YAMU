// Run inside an allocated agent workspace containing the notification helper.
def send(String kind, String event) {
    try {
        def delivered = false
        withEnv(["YAMU_NOTIFY_KIND=${kind}", "YAMU_NOTIFY_EVENT=${event}"]) {
            withCredentials([string(credentialsId: 'yamu-mattermost-webhook', variable: 'MATTERMOST_WEBHOOK')]) {
                def output = sh(returnStdout: true, script: '''set +x
                    python3 infra/ci/notify_mattermost.py
                ''').trim()
                delivered = output == 'Mattermost notification sent.'
            }
        }
        echo delivered ? 'Mattermost notification sent.' : 'WARNING: Mattermost notification unavailable; build result unchanged.'
        return delivered
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException interrupted) {
        // Preserve user cancellation; never convert an aborted build to success.
        throw interrupted
    } catch (Exception ignored) {
        echo 'WARNING: Mattermost notification unavailable; build result unchanged.'
        return false
    }
}

return this
