# Deploying to Google Cloud Run + Cloud SQL

Record of the commands used to deploy `chat-service`, in the order they were run. Secrets are never written here;
each one is typed into a prompt or read from a local file and stored in Secret Manager.

Commands are PowerShell (Windows). Every `gcloud` command passes `--project` explicitly, so the machine's default
gcloud project is never changed.

## Variables

```powershell
$PROJECT  = 'quarkustest-491617'        # existing project with billing enabled
$REGION   = 'asia-south1'               # Mumbai: close to SalesIQ's India data centre and the visitors
$SERVICE  = 'chat-service'              # Cloud Run service
$SQL      = 'chat-db'                   # Cloud SQL instance
$DB       = 'chat'                      # database name
$DB_USER  = 'chat'                      # database user
$REPO     = 'chat'                      # Artifact Registry repository
$VERSION  = 'v0.1.1'                    # git tag being deployed
$COMMIT   = 'bdf4b2a'                   # commit of that tag (git rev-parse --short v0.1.1)
$IMAGE    = "$REGION-docker.pkg.dev/$PROJECT/$REPO/$SERVICE"
```

## 1. Project

A dedicated project (`colourcoats-agent-5893`) was created first, but billing could not be linked to it (the billing
account's project limit was reached), so the existing test project `quarkustest-491617` is used. All resources
created below are named `chat-*` / `chat-service` so they can be removed precisely afterwards (see "Clean up").

```powershell
gcloud projects create colourcoats-agent-5893 --name="ColourCoats Agent"   # not used: billing could not be linked
gcloud billing projects describe $PROJECT --format="value(billingEnabled)" # True
```

## 2. Enable the APIs

Free; charges start only when resources are created.

```powershell
gcloud services enable sqladmin.googleapis.com cloudbuild.googleapis.com secretmanager.googleapis.com `
  run.googleapis.com artifactregistry.googleapis.com iam.googleapis.com --project $PROJECT
```

## 3. Cloud SQL (PostgreSQL 17 with pgvector)

Smallest shared-core instance, one zone, no automatic backups (a short-lived demo). Billed per hour until deleted.

```powershell
gcloud sql instances create $SQL --project $PROJECT --region $REGION --database-version POSTGRES_17 `
  --edition ENTERPRISE --tier db-f1-micro --storage-type SSD --storage-size 10 --availability-type zonal `
  --no-backup --async
```

## 4. Image repository and the app's service account

The service account starts with no permissions: Cloud SQL client now, read access to its own secrets in step 6.

```powershell
gcloud artifacts repositories create $REPO --repository-format docker --location $REGION `
  --description "chat-service images" --project $PROJECT
gcloud iam service-accounts create $SERVICE --display-name "chat-service (Cloud Run)" --project $PROJECT
$SA = "$SERVICE@$PROJECT.iam.gserviceaccount.com"
gcloud projects add-iam-policy-binding $PROJECT --member "serviceAccount:$SA" --role roles/cloudsql.client --condition None
```

## 5. Build the image (Cloud Build)

Run from the repository root with the release tag checked out (`git diff $VERSION -- chat-service` is empty).
The upload honours `.gitignore`, so `target/` and `logs/` are not sent.

```powershell
gcloud builds submit chat-service --project $PROJECT --region $REGION --tag "${IMAGE}:$VERSION" --suppress-logs
```

First attempt (`v0.1.0`) failed: `./mvnw: Permission denied`. Files uploaded from Windows carry no executable
bit, so the Dockerfile now runs `chmod +x mvnw` before using it (fixed in `v0.1.1`).

The fix was test-built from its branch before merging, then the same image was given the release tags (the merged
`chat-service` folder is identical to the tested commit, `git diff 823d44a v0.1.1 -- chat-service` is empty):

```powershell
gcloud builds submit chat-service --project $PROJECT --region $REGION --tag "${IMAGE}:test-823d44a" --suppress-logs
git tag -a v0.1.1 bdf4b2a -m "v0.1.1: Docker build makes the Maven wrapper executable (Cloud Build from Windows)"
git push origin v0.1.1
gcloud artifacts docker tags add "${IMAGE}:test-823d44a" "${IMAGE}:$VERSION"
gcloud artifacts docker tags add "${IMAGE}:test-823d44a" "${IMAGE}:$COMMIT"
```

## 6. Database, user and secrets

The database password is generated, set on the user and stored in Secret Manager without being printed.

```powershell
gcloud sql databases create $DB --instance $SQL --project $PROJECT
$bytes = New-Object byte[] 24; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$pw = ([Convert]::ToBase64String($bytes) -replace '[+/=]', 'x')
gcloud sql users create $DB_USER --instance $SQL --project $PROJECT --password $pw
$f = New-TemporaryFile; [IO.File]::WriteAllText($f, $pw)
gcloud secrets create chat-db-password --data-file $f --replication-policy automatic --project $PROJECT
Remove-Item $f; Remove-Variable pw, bytes
```

The OpenAI key and the operator password are typed by the owner (hidden input, written without a trailing newline
to a temporary file that is deleted afterwards):

```powershell
function New-ChatSecret($name, $prompt) {
  $v = [Net.NetworkCredential]::new('', (Read-Host $prompt -AsSecureString)).Password
  $f = New-TemporaryFile; [IO.File]::WriteAllText($f, $v)
  gcloud secrets create $name --data-file $f --replication-policy automatic --project $PROJECT
  Remove-Item $f
}
New-ChatSecret 'chat-openai-api-key'     'OpenAI API key'
New-ChatSecret 'chat-operator-password'  'Operator password for the lead console'
```
