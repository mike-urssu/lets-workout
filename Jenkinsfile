// Multibranch Pipeline. Every branch is tested; only main is pushed to GHCR and deployed.
//
// Jenkins setup:
//   - Global tool (JDK) named 'jdk21'; the agent must run Docker (Testcontainers). Jib builds the image without Docker.
//   - Credentials:
//       ghcr-credentials : Username/Password = GitHub user + PAT with write:packages
//       deploy-ssh-key   : SSH private key for the deploy server
//       deploy-host      : Secret text = user@host of the deploy server
//   - Optional environment GHCR_OWNER: GitHub user/org that owns the image (defaults to the GHCR username).
//   - Optional environment DEPLOY_PORT: SSH port of the deploy server (defaults to 22).
pipeline {
    agent any

    tools {
        jdk 'jdk21'
    }

    options {
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    environment {
        IMAGE_NAME = 'workout-backend'
        DEPLOY_DIR = '/opt/projects/workout'
    }

    stages {
        stage('Test') {
            steps {
                sh './gradlew clean test --no-daemon'
            }
            post {
                always {
                    junit 'build/test-results/test/*.xml'
                }
            }
        }

        stage('Publish image') {
            when { branch 'main' }
            steps {
                withCredentials([usernamePassword(credentialsId: 'ghcr-credentials',
                        usernameVariable: 'GHCR_USER', passwordVariable: 'GHCR_TOKEN')]) {
                    script {
                        // GHCR repository names must be lowercase.
                        def owner = (env.GHCR_OWNER ?: env.GHCR_USER).toLowerCase()
                        env.IMAGE = "ghcr.io/${owner}/${env.IMAGE_NAME}"
                        // <semantic version from build.gradle.kts>-<git sha>
                        def version = sh(script: "./gradlew -q properties --property version --no-daemon | sed -n 's/^version: //p'",
                                returnStdout: true).trim()
                        def sha = sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim()
                        env.IMAGE_TAG = "${version}-${sha}"
                    }
                    // Jib reads GHCR_USER/GHCR_TOKEN from the environment (build.gradle.kts).
                    sh './gradlew jib --no-daemon -Djib.to.image="$IMAGE:$IMAGE_TAG" -Djib.to.tags=latest'
                }
            }
        }

        stage('Deploy') {
            when { branch 'main' }
            steps {
                withCredentials([string(credentialsId: 'deploy-host', variable: 'DEPLOY_HOST')]) {
                    sshagent(credentials: ['deploy-ssh-key']) {
                        // The server pulls with its own read-only GHCR login (see deploy setup), so the
                        // write token never leaves Jenkins.
                        sh '''
                            PORT="${DEPLOY_PORT:-22}"
                            SSH="ssh -p $PORT -o StrictHostKeyChecking=accept-new $DEPLOY_HOST"
                            COMPOSE="docker compose --env-file .env --env-file release.env"

                            $SSH "mkdir -p $DEPLOY_DIR"
                            scp -P "$PORT" -o StrictHostKeyChecking=accept-new deploy/compose.yaml "$DEPLOY_HOST:$DEPLOY_DIR/compose.yaml"
                            printf 'IMAGE=%s\\nIMAGE_TAG=%s\\n' "$IMAGE" "$IMAGE_TAG" | $SSH "cat > $DEPLOY_DIR/release.env"
                            $SSH "cd $DEPLOY_DIR && $COMPOSE pull app && $COMPOSE up -d"
                        '''
                    }
                }
            }
        }

        stage('Verify') {
            when { branch 'main' }
            steps {
                withCredentials([string(credentialsId: 'deploy-host', variable: 'DEPLOY_HOST')]) {
                    sshagent(credentials: ['deploy-ssh-key']) {
                        // An unauthenticated call answering 401 means the app started and reached its DB.
                        sh '''
                            ssh -p "${DEPLOY_PORT:-22}" -o StrictHostKeyChecking=accept-new "$DEPLOY_HOST" '
                                for i in $(seq 1 30); do
                                    code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/v1/auth/session)
                                    [ "$code" = "401" ] && exit 0
                                    sleep 2
                                done
                                echo "app did not become ready (last status: $code)"
                                exit 1
                            '
                        '''
                    }
                }
            }
        }
    }
}
