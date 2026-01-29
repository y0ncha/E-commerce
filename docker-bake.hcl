group "default" {
  targets = ["app"]
}

target "app" {
  context    = "."
  dockerfile = "Dockerfile"

  platforms = ["linux/amd64", "linux/arm64"]

  tags = [
    "y0ncha/REPO_NAME:latest"
  ]

  push = true

  cache_to = [
    "type=registry,ref=y0ncha/REPO_NAME:buildcache,mode=max"
  ]

  cache_from = [
    "type=registry,ref=y0ncha/REPO_NAME:buildcache"
  ]
}