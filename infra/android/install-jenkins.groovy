// S15P31E103-263. Run once in the authenticated Jenkins administrator script console.
// No key is present in this script. Bootstrap files are installed privately by the operator.
import jenkins.model.Jenkins
import com.cloudbees.hudson.plugins.folder.Folder
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SecretBytes
import com.cloudbees.plugins.credentials.domains.Domain
import org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl

def instance = Jenkins.get()
def parent = instance.getItemByFullName('yamu', Folder)
if (parent == null) throw new IllegalStateException('Existing yamu folder is required')
def folder = parent.getItem('mobile')
if (folder == null) folder = parent.createProject(Folder, 'mobile')
if (!(folder instanceof Folder)) throw new IllegalStateException('mobile is not a Folder')

def source = instance.getItemByFullName('yamu/ci').definition.scm
if (source.userRemoteConfigs.size() != 1) throw new IllegalStateException('Expected one trusted Git remote')
def remote = source.userRemoteConfigs[0]
if (!(remote.url ==~ /https:\/\/lab\.ssafy\.com\/s15-final\/S15P31E103(?:\.git)?/)
        || !(remote.credentialsId ==~ /[A-Za-z0-9_-]+/)) {
    throw new IllegalStateException('Unexpected source-control settings')
}

def marker = 'Managed APK pipeline / S15P31E103-263'
['apk-mock', 'apk-mock-verification'].each { name ->
    def existing = folder.getItem(name)
    if (existing != null && (!existing.description?.contains(marker) || existing.isBuilding())) {
        throw new IllegalStateException('Existing job is unmanaged or running; no overwrite')
    }
}

def store = CredentialsProvider.lookupStores(folder).find { it.context == folder }
if (store == null) throw new IllegalStateException('Folder credential store is required')
def id = 'yamu-apk-s3-credentials'
def credential = store.getCredentials(Domain.global()).find { it.id == id }
def bootstrap = new File(instance.rootDir, '.yamu-apk-bootstrap/s3-publisher.ini')
if (credential == null) {
    if (!bootstrap.isFile()) throw new IllegalStateException('Private credential bootstrap is missing')
    def created = new FileCredentialsImpl(CredentialsScope.GLOBAL, id,
        'Prefix-limited mock APK uploader; S15P31E103-263', 'apk-publisher.ini',
        SecretBytes.fromBytes(bootstrap.bytes))
    if (!store.addCredentials(Domain.global(), created)) throw new IllegalStateException('Credential registration failed')
} else if (!(credential instanceof FileCredentialsImpl)) {
    throw new IllegalStateException('Existing credential has an unexpected type')
} else if (bootstrap.exists()) {
    def same = credential.content.withCloseable { stream -> Arrays.equals(stream.bytes, bootstrap.bytes) }
    if (!same) throw new IllegalStateException('Existing credential differs; automatic rotation is forbidden')
}
// Jenkins now encrypts the credential; remove only this exact, one-time bootstrap file.
if (bootstrap.exists() && !bootstrap.delete()) throw new IllegalStateException('Bootstrap cleanup failed')

def definition = { String branch, boolean disabled ->
    return """<flow-definition plugin="workflow-job">
<description>${marker}</description><keepDependencies>false</keepDependencies>
<properties><org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty/></properties>
<definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition" plugin="workflow-cps">
<scm class="hudson.plugins.git.GitSCM" plugin="git"><configVersion>2</configVersion>
<userRemoteConfigs><hudson.plugins.git.UserRemoteConfig><url>${remote.url}</url><credentialsId>${remote.credentialsId}</credentialsId></hudson.plugins.git.UserRemoteConfig></userRemoteConfigs>
<branches><hudson.plugins.git.BranchSpec><name>${branch}</name></hudson.plugins.git.BranchSpec></branches>
<doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations><submoduleCfg class="empty-list"/>
<extensions><hudson.plugins.git.extensions.impl.PathRestriction><includedRegions>fe/android/.*
infra/android/.*</includedRegions><excludedRegions></excludedRegions></hudson.plugins.git.extensions.impl.PathRestriction></extensions></scm>
<scriptPath>infra/android/Jenkinsfile</scriptPath><lightweight>true</lightweight></definition>
<disabled>${disabled}</disabled></flow-definition>"""
}

// The develop job is created disabled until a human merges the MR.
def main = folder.getItem('apk-mock')
if (main == null) main = folder.createProjectFromXML('apk-mock', new ByteArrayInputStream(definition('*/develop', true).getBytes('UTF-8')))
def check = folder.getItem('apk-mock-verification')
def createdCheck = check == null
if (createdCheck) check = folder.createProjectFromXML('apk-mock-verification', new ByteArrayInputStream(
    definition('*/chore/infra/S15P31E103-263-jenkins-mock-apk', false).getBytes('UTF-8')))
if (createdCheck) check.scheduleBuild2(0)
println('APK verification job queued; develop APK job remains disabled until human merge. No secret values printed.')
