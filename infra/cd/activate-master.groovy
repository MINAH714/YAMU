// Run in the administrator console AFTER human merge to master and host installation.
// This script configures polling only; it never schedules a build or approves input.
// Default is read-only. Set binding variable APPLY_MASTER_CD=true to activate.
import jenkins.model.Jenkins
import jenkins.scm.api.SCMFileSystem
import hudson.triggers.SCMTrigger
import hudson.model.ParametersDefinitionProperty
import hudson.model.BooleanParameterDefinition
import hudson.model.StringParameterDefinition

def instance = Jenkins.get()
def cd = instance.getItemByFullName('yamu/cd')
def ci = instance.getItemByFullName('yamu/ci-master')
[[cd, 'Jenkinsfile.cd'], [ci, 'Jenkinsfile']].each { pair ->
    def job = pair[0]
    if (job == null || job.isBuilding() || job.isInQueue() ||
        job.definition.scriptPath != pair[1] ||
        job.definition.scm.branches*.name != ['*/master']) {
        throw new IllegalStateException('Unexpected or busy master job; no settings changed')
    }
    def fs = SCMFileSystem.of(job)
    try {
        if (fs == null || !fs.root.child(pair[1]).exists()) {
            throw new IllegalStateException('Reviewed pipeline must exist on master first')
        }
        def source = fs.root.child(pair[1]).contentAsString()
        if (!source.contains('RELEASE_REVISION')) {
            throw new IllegalStateException('Master lacks the reviewed release binding')
        }
    } finally {
        fs?.close()
    }
}
if (!binding.hasVariable('APPLY_MASTER_CD') || binding.getVariable('APPLY_MASTER_CD') != true) {
    println('Preflight passed; no Job settings changed. Install reviewed host helper before activation.')
    return
}
def old = ci.getProperty(ParametersDefinitionProperty)
def definitions = old == null ? [] : old.parameterDefinitions.findAll {
    !(it.name in ['FULL_CI', 'RELEASE_REVISION'])
}
definitions += new BooleanParameterDefinition('FULL_CI', true, 'Run complete release checks')
definitions += new StringParameterDefinition('RELEASE_REVISION', '', 'Exact master commit requested by CD')
ci.addProperty(new ParametersDefinitionProperty(definitions))
ci.triggers.values().findAll { it instanceof SCMTrigger }.each { ci.removeTrigger(it) }
ci.setDisabled(false)
ci.save()
cd.addTrigger(new SCMTrigger('H/5 * * * *'))
cd.setDisabled(false)
cd.save()
println('Master CD polling enabled. No build scheduled; yamu approval remains mandatory.')
