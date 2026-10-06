#!/bin/sh
set -eu

awslocal sqs create-queue --queue-name peoplecore-notifications
awslocal sqs create-queue --queue-name peoplecore-notifications-dlq
