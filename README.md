Enterprise Banking & Payment Platform

A production-style banking and bill payment platform built using Spring Boot, Kafka, PostgreSQL, Auth0, and Event-Driven Microservices Architecture.

## Architecture Overview

This project demonstrates how modern banks process customer onboarding, account management, transactions, bill payments, and settlement workflows using secure and scalable microservices.

### Microservices

- Customer Service
- AuthUser Service
- Account Service
- Payment Orchestrator Service
- Settlement Service
- Commons Security
- Commons DTO
- Commons Observability

---

## Key Features

### Customer Management
- Customer onboarding
- Customer profile management
- Customer validation

### Authentication & Authorization
- Auth0 integration
- OAuth2 Resource Server
- JWT validation
- Scope-based access control
- Token relay between microservices

### Account Management
- Create accounts
- Fetch account balances
- Account holds and releases
- Debit and credit operations
- Optimistic locking
- Idempotent requests

### Transactions
- Transaction ledger
- Debit transactions
- Credit transactions
- Server-side pagination
- Transaction history APIs

### Bill Payments
- Biller validation
- Fund reservation (Hold)
- Payment orchestration
- Kafka event publishing
- Batch settlement processing
- Retry and failure handling

---

## Event Driven Architecture

Kafka is used for asynchronous communication between services.

### Kafka Topics

```text
billpay.requested
billpay.enqueued
bill.batch.ready
bill.batch.submitted
billpay.status



#FLOW

Customer
    |
    v
Payment Orchestrator
    |
    v
Account Service (Hold Funds)
    |
    v
Kafka (billpay.requested)
    |
    v
BillPay Worker
    |
    v
Kafka (bill.batch.ready)
    |
    v
Settlement Service
    |
    v
Central Settlement Network


## Design Patterns Used

Saga Pattern
Outbox Pattern
Event-Driven Architecture
Optimistic Locking
Idempotency
Token Relay
Retry & Dead Letter Queue (DLQ)

## Technology Stack

  #Backend
    Java 22
     Spring Boot 3
      Spring Security
          Spring Data JPA
          Spring Cloud OpenFeign

 #Messaging
   Apache Kafka
   Database
   PostgreSQL

# Security
  Auth0
  OAuth2
  JWT
#DevOps
  Docker
  Docker Compose
  GitHub


BankingPayment-MicroService
│
├── AuthUser-develop
├── CustomerService-develop
├── AccountService-develop
├── PaymentOrchestrator-develop
├── SettlementService-develop
├── commons-security-develop
├── commons-dto-develop
└── commons-observability-develop




##Learning Outcomes

   1)This project covers:

Enterprise Microservices Architecture
Banking Domain Design
Event Driven Systems
Distributed Transactions using Saga Pattern
Kafka Producers and Consumers
Secure APIs with Auth0
Account and Transaction Management
Bill Payment and Settlement Processing



Author

Aakash Chaurasiya

Java Full Stack Developer | Backend & Scalable Systems Enthusiast | Exploring Agentic AI
