SHELL := /bin/bash
JAVA_HOME ?= /opt/homebrew/opt/openjdk@21
export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(PATH)

.PHONY: help build test up down logs smoke clean

help:
	@grep -E '^[a-z-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-10s %s\n", $$1, $$2}'

build: ## Compile both modules and run the test suite
	mvn -B -ntp verify

test: ## Run the test suite only
	mvn -B -ntp test

up: ## Build images and start the whole stack
	docker compose up --build -d

down: ## Stop the stack (add ARGS=-v to drop the database volume)
	docker compose down $(ARGS)

logs: ## Tail logs from all services
	docker compose logs -f

smoke: ## Hit every M0 endpoint against a running stack
	./scripts/smoke.sh

clean: ## Remove build output
	mvn -B -ntp clean
