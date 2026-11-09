// S15P31E103-273. New isolated pilot only; no existing Job or credential is changed.
import jenkins.model.Jenkins
import com.cloudbees.hudson.plugins.folder.Folder
import hudson.model.ParametersAction
import hudson.model.StringParameterValue

def instance = Jenkins.get()
def folder = instance.getItemByFullName('yamu', Folder)
if (folder == null) throw new IllegalStateException('Existing yamu folder required')
def name = 'cd-stages-verification'
def marker = 'Managed isolated CD stages / S15P31E103-273'
def existing = folder.getItem(name)
if (existing != null) {
    if (!existing.description?.contains(marker)) throw new IllegalStateException('Unmanaged Job collision')
    println('Managed pilot already exists; no duplicate build queued.')
    return
}
def scm = instance.getItemByFullName('yamu/ci').definition.scm
if (scm.userRemoteConfigs.size() != 1) throw new IllegalStateException('Expected one Git remote')
def remote = scm.userRemoteConfigs[0]
if (remote.url != 'https://lab.ssafy.com/s15-final/S15P31E103.git' || remote.credentialsId != 'gitlab-yamu-read') {
    throw new IllegalStateException('Unexpected Git settings')
}
def xml = """<flow-definition plugin="workflow-job"><description>${marker}</description>
<keepDependencies>false</keepDependencies><properties>
<hudson.model.ParametersDefinitionProperty><parameterDefinitions>
<hudson.model.StringParameterDefinition><name>CI_BUILD</name><defaultValue>21</defaultValue></hudson.model.StringParameterDefinition>
<hudson.model.ChoiceParameterDefinition><name>SCENARIO</name><choices class="java.util.Arrays\$ArrayList"><a class="string-array"><string>normal</string><string>fail-health</string></a></choices></hudson.model.ChoiceParameterDefinition>
</parameterDefinitions></hudson.model.ParametersDefinitionProperty>
<org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty/></properties>
<definition class="org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition" plugin="workflow-cps">
<scm class="hudson.plugins.git.GitSCM" plugin="git"><configVersion>2</configVersion>
<userRemoteConfigs><hudson.plugins.git.UserRemoteConfig><url>${remote.url}</url><credentialsId>${remote.credentialsId}</credentialsId></hudson.plugins.git.UserRemoteConfig></userRemoteConfigs>
<branches><hudson.plugins.git.BranchSpec><name>*/chore/infra/S15P31E103-273-cd-stage-visualization</name></hudson.plugins.git.BranchSpec></branches>
<doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations><submoduleCfg class="empty-list"/><extensions/>
</scm><scriptPath>infra/cd-stages/Jenkinsfile</scriptPath><lightweight>true</lightweight></definition>
<disabled>false</disabled></flow-definition>"""
def pilot = folder.createProjectFromXML(name, new ByteArrayInputStream(xml.getBytes('UTF-8')))
pilot.scheduleBuild2(0, new ParametersAction(new StringParameterValue('CI_BUILD', '21'), new StringParameterValue('SCENARIO', 'normal')))
println('Isolated CD stage pilot created and queued once. Existing Jobs and credentials preserved.')
