// S15P31E103-268. Execute once in the authenticated administrator script console.
import jenkins.model.Jenkins
import com.cloudbees.hudson.plugins.folder.Folder
import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SecretBytes
import com.cloudbees.plugins.credentials.domains.Domain
import org.jenkinsci.plugins.plaincredentials.impl.FileCredentialsImpl
import hudson.model.ParametersAction
import hudson.model.StringParameterValue
import hudson.model.BooleanParameterValue

def instance = Jenkins.get()
def parent = instance.getItemByFullName('yamu', Folder)
if (parent == null) throw new IllegalStateException('Existing yamu folder required')
def source = instance.getItemByFullName('yamu/ci').definition.scm
if (source.userRemoteConfigs.size() != 1) throw new IllegalStateException('Expected one Git remote')
def remote = source.userRemoteConfigs[0]
if (remote.url != 'https://lab.ssafy.com/s15-final/S15P31E103.git'
        || remote.credentialsId != 'gitlab-yamu-read') throw new IllegalStateException('Unexpected Git settings')
def bootstrapDir = new File(instance.rootDir, '.yamu-gitlab-bootstrap')
def pilot = new groovy.json.JsonSlurperClassic().parse(new File(bootstrapDir, 'verification.json'))
if (!(pilot.iid instanceof Number) || pilot.iid.intValue() <= 0) throw new IllegalStateException('Pilot MR required')
def marker = 'Managed GitLab MR pipeline / S15P31E103-268'
def folder = parent.getItem('gitlab')
if (folder != null && !(folder instanceof Folder)) throw new IllegalStateException('gitlab is not a folder')
if (folder == null) folder = parent.createProject(Folder, 'gitlab')
['mr-ci', 'mr-ci-verification'].each { name ->
    def job = folder.getItem(name)
    if (job != null && (!job.description?.contains(marker) || job.isBuilding())) {
        throw new IllegalStateException('Existing job unmanaged or running; no overwrite')
    }
}
def store = CredentialsProvider.lookupStores(parent).find { it.context == parent }
if (store == null) throw new IllegalStateException('Folder credential store required')
def id = 'yamu-gitlab-status-api'
def tokenFile = new File(bootstrapDir, 'api.token')
def credential = store.getCredentials(Domain.global()).find { it.id == id }
if (credential == null) {
    if (!tokenFile.isFile()) throw new IllegalStateException('Private token bootstrap missing')
    def created = new FileCredentialsImpl(CredentialsScope.GLOBAL, id,
        'GitLab project status API / S15P31E103-268', 'gitlab-api.token', SecretBytes.fromBytes(tokenFile.bytes))
    if (!store.addCredentials(Domain.global(), created)) throw new IllegalStateException('Credential registration failed')
} else if (!(credential instanceof FileCredentialsImpl)) {
    throw new IllegalStateException('Unexpected credential type')
} else if (tokenFile.exists()) {
    def same = credential.content.withCloseable { Arrays.equals(it.bytes, tokenFile.bytes) }
    if (!same) throw new IllegalStateException('Existing credential differs; no automatic rotation')
}
if (tokenFile.exists() && !tokenFile.delete()) throw new IllegalStateException('Bootstrap cleanup failed')
def definition = { String branch, boolean disabled ->
    """<flow-definition plugin="workflow-job"><description>${marker}</description>
<keepDependencies>false</keepDependencies><properties>
<hudson.model.ParametersDefinitionProperty><parameterDefinitions>
<hudson.model.StringParameterDefinition><name>MR_IID</name><defaultValue></defaultValue></hudson.model.StringParameterDefinition>
<hudson.model.BooleanParameterDefinition><name>RETRY</name><defaultValue>false</defaultValue></hudson.model.BooleanParameterDefinition>
</parameterDefinitions></hudson.model.ParametersDefinitionProperty>
<org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty/></properties>
<definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition" plugin="workflow-cps">
<scm class="hudson.plugins.git.GitSCM" plugin="git"><configVersion>2</configVersion>
<userRemoteConfigs><hudson.plugins.git.UserRemoteConfig><url>${remote.url}</url><credentialsId>${remote.credentialsId}</credentialsId></hudson.plugins.git.UserRemoteConfig></userRemoteConfigs>
<branches><hudson.plugins.git.BranchSpec><name>${branch}</name></hudson.plugins.git.BranchSpec></branches>
<doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations><submoduleCfg class="empty-list"/><extensions/>
</scm><scriptPath>infra/gitlab/Jenkinsfile.mr</scriptPath><lightweight>true</lightweight></definition>
<disabled>${disabled}</disabled></flow-definition>"""
}
if (folder.getItem('mr-ci') == null) folder.createProjectFromXML('mr-ci', new ByteArrayInputStream(definition('*/develop', true).getBytes('UTF-8')))
def check = folder.getItem('mr-ci-verification')
if (check == null) {
    check = folder.createProjectFromXML('mr-ci-verification', new ByteArrayInputStream(
        definition('*/chore/infra/S15P31E103-268-gitlab-jenkins-status', false).getBytes('UTF-8')))
    check.scheduleBuild2(0, new ParametersAction(new StringParameterValue('MR_IID', pilot.iid.toString()), new BooleanParameterValue('RETRY', false)))
}
println('MR verification registered and queued once. Normal MR job remains disabled until human merge. No secrets printed.')
