pipeline {
    agent { label 'yamu-ci' }

    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        skipStagesAfterUnstable()
        timeout(time: 45, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '10', artifactNumToKeepStr: '5'))
        timestamps()
    }

    triggers {
        pollSCM('H/5 * * * *')
    }

    environment {
        CI = 'true'
        TZ = 'UTC'
        NODE_OPTIONS = '--max-old-space-size=1024'
        VITE_API_BASE_URL = '/api'
    }

    stages {
        stage('Checkout') {
            steps {
                // Remove old results and generated files before checking out this revision.
                deleteDir()
                checkout scm
                sh 'git rev-parse HEAD > ci-revision.txt'
            }
        }
        stage('Build environment') {
            steps {
                sh 'sh infra/ci/check-environment.sh'
                sh 'python3 infra/ci/test_check_test_results.py'
            }
        }
        stage('BE tests') {
            steps {
                dir('be') {
                    sh 'sh ./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx768m -XX:MaxMetaspaceSize=384m" clean test'
                }
                sh 'python3 infra/ci/check-test-results.py be/build/test-results/test'
            }
            post {
                always {
                    junit testResults: 'be/build/test-results/test/TEST-*.xml', allowEmptyResults: false
                }
            }
        }
        stage('BE JAR') {
            steps {
                dir('be') {
                    sh 'sh ./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx768m -XX:MaxMetaspaceSize=384m" bootJar'
                }
            }
        }
        stage('POS lint and build') {
            steps {
                dir('fe/pos') {
                    sh 'npm ci --no-audit --no-fund'
                    sh 'npm run lint'
                    sh 'npm run build'
                }
            }
        }
        stage('Mobile typecheck') {
            steps {
                dir('fe/mobile') {
                    sh 'npm ci --no-audit --no-fund'
                    sh './node_modules/.bin/tsc --noEmit'
                }
            }
        }
        stage('Archive') {
            steps {
                archiveArtifacts artifacts: 'ci-revision.txt,be/build/libs/*.jar,fe/pos/dist/**', fingerprint: true
            }
        }
    }

    post {
        cleanup {
            deleteDir()
        }
    }
}
