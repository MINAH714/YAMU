// S15P31E103-271. Revert only the managed views; never delete Jobs or build history.
import jenkins.model.Jenkins
import com.cloudbees.hudson.plugins.folder.Folder
def instance = Jenkins.get()
def folder = instance.getItemByFullName('yamu', Folder)
if (folder == null) throw new IllegalStateException('Existing yamu folder required')
def backup = new File(instance.rootDir, '.yamu-team-bootstrap/previous-primary-view.txt')
if (!backup.isFile()) throw new IllegalStateException('Previous primary view record required')
def previous = folder.getView(backup.text.trim())
if (previous == null || previous.viewName in ['Team', 'CI', 'CD', 'APK']) {
    throw new IllegalStateException('Previous view unavailable; stop for manual review')
}
def views = ['Team', 'CI', 'CD', 'APK'].collect { folder.getView(it) }.findAll { it != null }
if (views.any { !it.description?.contains('[YAMU_TEAM_VIEW:S15P31E103-271]') }) {
    throw new IllegalStateException('Unmanaged view detected; no changes made')
}
folder.setPrimaryView(previous)
views.each { folder.deleteView(it) }
folder.save()
println('Managed team views removed; previous primary view restored. Jobs and build history preserved.')
