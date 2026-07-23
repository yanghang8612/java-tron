<h1 align="center">
  <br>
  <img width=20% src="https://github.com/tronprotocol/wiki/blob/master/images/java-tron.jpg?raw=true">
</h1>
<h4 align="center">
  Java implementation of the <a href="https://tron.network">TRON Protocol</a>
</h4>

<p align="center">
  <a href="https://discord.gg/hqKvyAM"><img src="https://img.shields.io/badge/chat-on%20discord-7289da.svg"></a>
  <a href="https://github.com/tronprotocol/java-tron/issues"><img src="https://img.shields.io/github/issues/tronprotocol/java-tron.svg"></a>
  <a href="https://github.com/tronprotocol/java-tron/pulls"><img src="https://img.shields.io/github/issues-pr/tronprotocol/java-tron.svg"></a>
  <a href="https://github.com/tronprotocol/java-tron/graphs/contributors"><img src="https://img.shields.io/github/contributors/tronprotocol/java-tron.svg"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/tronprotocol/java-tron.svg"></a>
</p>

## Table of Contents

- [What’s TRON?](#whats-tron)
- [Building the Source Code](#building-the-source-code)
- [Executables](#executables)
- [Running java-tron](#running-java-tron)
- [Community](#community)
- [Contribution](#contribution)
- [Resources](#resources)
- [Integrity Check](#integrity-check)
- [License](#license)

# What's TRON?

TRON is building the foundational infrastructure for the decentralized internet ecosystem with a focus on high-performance, scalability, and security.

- TRON Protocol: High-throughput (2000+ TPS), scalable blockchain OS (DPoS consensus) powering the TRON ecosystem.
- TRON Virtual Machine (TVM): EVM-compatible smart-contract engine for fast smart-contract execution.

# Building the Source Code
Before building java-tron, make sure you have:
- Hardware with at least 4 CPU cores, 16 GB RAM, 10 GB free disk space for a smooth compilation process.
- Operating system: `Linux` or `macOS` (`Windows` is not supported).
- Git and correct JDK (version `8` or `17`) installed based on your CPU architecture.

There are two ways to install the required dependencies:

- **Option 1: Automated script (recommended for quick setup)**

  Use the provided [`install_dependencies.sh`](install_dependencies.sh) script:

  ```bash
  chmod +x install_dependencies.sh
  ./install_dependencies.sh
  ```
  > **Note**: For production-grade stability with JDK 8 on x86_64 architecture, Oracle JDK 8 is strongly recommended (the script installs OpenJDK 8).

- **Option 2: Manual installation**

  Follow the [Prerequisites and Installation Guide](https://tronprotocol.github.io/documentation-en/using_javatron/installing_javatron/#prerequisites-before-compiling-java-tron) for step-by-step instructions.

Once all dependencies have been installed, download and compile java-tron by executing:
```bash
git clone https://github.com/tronprotocol/java-tron.git
cd java-tron
git checkout -t origin/master
./gradlew clean build -x test
```
* The parameter `-x test` indicates skipping the execution of test cases. 
* If you encounter any error please refer to the [Compiling java-tron Source Code](https://tronprotocol.github.io/documentation-en/using_javatron/installing_javatron/#compiling-java-tron-source-code) documentation for troubleshooting steps.

# Executables

The java-tron project comes with several runnable artifacts and helper scripts found in the project root and build directories.

|     Artifact/Script     | Description |
| :---------------------- | :---------- |
| **`FullNode.jar`**      | Main TRON node executable (generated in `build/libs/` after a successful build following the above guidance). Runs as a full node by default. `java -jar FullNode.jar --help` for command line options|
| **`Toolkit.jar`** | Node management utility (generated in `build/libs/`): partition, prune, copy, convert DBs; shadow-fork tool. [Usage](https://tronprotocol.github.io/documentation-en/using_javatron/toolkit/#toolkit-a-java-tron-node-maintenance-suite) |
| **`start.sh`**          | Quick start script (x86_64, JDK 8) to download/build/run `FullNode.jar`. See the tool [guide](./shell.md). |
| **`start.sh.simple`**   | Quick start script template (ARM64, JDK 17). See usage notes inside the script. |

# Running java-tron

## Hardware Requirements for Mainnet

| Deployment Tier | CPU Cores | Memory | High-performance SSD Storage    | Network Downstream |
|--------------------------|-------|--------|---------------------------|-----------------|
| FullNode (Minimum)        | 8 | 16 GB | 200 GB ([Lite](https://tronprotocol.github.io/documentation-en/using_javatron/litefullnode/#lite-fullnode))                  | ≥ 5 MBit/sec  |
| FullNode (Stable)         | 8 | 32 GB | 200 GB (Lite) / 3.5 TB (Full) | ≥ 5 MBit/sec  |
| FullNode (Recommend)      | 16+ | 32 GB+ | 4 TB         | ≥ 50 MBit/sec  |
| Super Representative      | 32+ | 64 GB+ | 4 TB              | ≥ 50 MBit/sec  |

> **Note**: For test networks, where transaction volume is significantly lower, you may operate with reduced hardware specifications.

## Launching a full node

A full node acts as a gateway to the TRON network, exposing comprehensive interfaces via HTTP and RPC APIs. Through these endpoints, clients may execute asset transfers, deploy smart contracts, and invoke on-chain logic. It must join a TRON network to participate in the network's consensus and transaction processing.

### Network Types

The TRON network is mainly divided into:

- **Main Network (Mainnet)**  
  The primary public blockchain where real value (TRX, TRC-20 tokens, etc.) is transacted, secured by a massive decentralized network.

- **[Nile Test Network (Testnet)](https://nileex.io/)**  
  A forward-looking testnet where new features and governance proposals are launched first for developers to experience. Consequently, its codebase is typically ahead of the Mainnet.

- **[Shasta Testnet](https://shasta.tronex.io/)**  
  Closely mirrors the Mainnet’s features and governance proposals. Its network parameters and software versions are kept in sync with the Mainnet, providing developers with a highly realistic environment for final testing.

- **Private Networks**  
  Customized TRON networks set up by private entities for testing, development, or specific use cases.

Network selection is performed by specifying the appropriate configuration file upon full-node startup. Built-in configuration template: [reference.conf](common/src/main/resources/reference.conf); Mainnet configuration: [config.conf](framework/src/main/resources/config.conf); Nile testnet configuration: [config-nile.conf](https://github.com/tron-nile-testnet/nile-testnet/blob/master/framework/src/main/resources/config-nile.conf)

### 1. Join the TRON main network
Launch a main-network full node with the built-in default configuration:
```bash
java -jar ./build/libs/FullNode.jar
```

> For production deployments or long-running Mainnet nodes, please refer to the [JVM Parameter Optimization for FullNode](https://tronprotocol.github.io/documentation-en/using_javatron/installing_javatron/#jvm-parameter-optimization-for-mainnet-fullnode-deployment) guide for the recommended Java command configuration.

Using the below command, you can monitor the blocks syncing progress:
```bash
tail -f ./logs/tron.log
```

Use [TronScan](https://tronscan.org/#/), TRON's official block explorer, to view main network transactions, blocks, accounts, witness voting, and governance metrics, etc.

### 2. Join Nile test network
Utilize the `-c` flag to direct the node to the configuration file corresponding to the desired network. Since Nile Testnet may incorporate features not yet available on the Mainnet, it is **strongly advised** to compile the source code following the [Building the Source Code](https://github.com/tron-nile-testnet/nile-testnet/blob/master/README.md#building-the-source-code) instructions for the Nile Testnet.

```bash
java -jar ./build/libs/FullNode.jar -c config-nile.conf
```

Nile resources: explorer, faucet, wallet, developer docs, and network statistics at [nileex.io](https://nileex.io/).

### 3. Access Shasta test network
Shasta does not accept public node peers. Programmatic access is available via TronGrid endpoints; see [TronGrid Service](https://developers.tron.network/docs/trongrid) for details.

Shasta resources: explorer, faucet, wallet, developer docs, and network statistics at [shasta.tronex.io](https://shasta.tronex.io/).

### 4. Set up a private network
To set up a private network for testing or development, follow the [Private Network guidance](https://tronprotocol.github.io/documentation-en/using_javatron/private_network/).

## Running a super representative node

To operate the node as a Super Representative (SR), append the `--witness` parameter to the standard launch command. An SR node inherits every capability of a FullNode and additionally participates in block production. Refer to the [Super Representative documentation](https://tronprotocol.github.io/documentation-en/mechanism-algorithm/sr/) for eligibility requirements.

Fill in the private key of your SR account into the `localwitness` list in the configuration file. Here is an example:

```
 localwitness = [
    <your_private_key>
 ]
```
Check [Starting a Block Production Node](https://tronprotocol.github.io/documentation-en/using_javatron/installing_javatron/#starting-a-block-production-node) for more details.
You could also test the process by connecting to a testnet or setting up a private network.

## Programmatically interfacing FullNode

Once the FullNode starts successfully, interaction with the TRON network is facilitated through a comprehensive suite of programmatic interfaces exposed by java-tron:
- **HTTP API**: See the complete [HTTP API reference and endpoint list](https://tronprotocol.github.io/documentation-en/api/http/).
- **gRPC**: High-performance APIs suitable for service-to-service integration. See the supported [gRPC reference](https://tronprotocol.github.io/documentation-en/api/rpc/).
- **JSON-RPC**: Provides Ethereum-compatible JSON-RPC methods for logs, transactions and contract calls, etc. See the supported [JSON-RPC methods](https://tronprotocol.github.io/documentation-en/api/json-rpc/).

Enable or disable each interface in the configuration file:

```
node {
  http {
    fullNodeEnable = true
    fullNodePort   = 8090
  }

  jsonrpc {
    httpFullNodeEnable = true
    httpFullNodePort   = 8545
  }

  rpc {
    enable = true
    port   = 9090
  }
}
```
When exposing any of these APIs to a public interface, ensure the node is protected with appropriate authentication, rate limiting, and network access controls in line with your security requirements.

Public hosted HTTP endpoints for both mainnet and testnet are provided by TronGrid. Please refer to the [TRON Network HTTP Endpoints](https://developers.tron.network/docs/connect-to-the-tron-network#tron-network-http-endpoints) for the latest list. For supported methods and request formats, see the HTTP API reference above.

# Community

[TRON Developers & SRs](https://discord.gg/hqKvyAM) is TRON's official Discord channel. Feel free to join this channel if you have any questions.

The [Core Devs Community](https://t.me/troncoredevscommunity) and [TRON Official Developer Group](https://t.me/TronOfficialDevelopersGroupEn) are Telegram channels specifically designed for java-tron community developers to engage in technical discussions.

# Contribution

Thank you for considering to help out with the source code! If you'd like to contribute to java-tron, please see the [Contribution Guide](./CONTRIBUTING.md) for more details.

# Resources

- [Medium](https://medium.com/@coredevs) — Official technical articles from the java-tron core development team.
- [Documentation](https://tronprotocol.github.io/documentation-en/) and [TRON Developer Hub](https://developers.tron.network/) — Primary documentation for java-tron developers.
- [TronScan](https://tronscan.org/#/) — TRON mainnet blockchain explorer.
- [Nile Test Network](http://nileex.io/) — A stable test network for TRON development and testing.
- [Shasta Test Network](https://shasta.tronex.io/) — A stable test network mirroring mainnet features.
- [Wallet-cli](https://github.com/tronprotocol/wallet-cli) — Command-line wallet for the TRON network.
- [TIP](https://github.com/tronprotocol/tips) — TRON Improvement Proposals describing standards for the TRON network.
- [TP](https://github.com/tronprotocol/tips/tree/master/tp) — TRON Protocols already implemented but not yet published as TIPs.

# Integrity Check

- After January 3, 2023, the release files will be signed using a GPG key pair, and the correctness of the signature will be verified using the following public key:
  ```
  pub: 1254 F859 D2B1 BD9F 66E7 107D F859 BCB4 4A28 290B
  uid: build@tron.network
  ```

# License

java-tron is released under the [LGPLv3 license](https://github.com/tronprotocol/java-tron/blob/master/LICENSE).
=======
# java-tron-evnet



## Getting started

To make it easy for you to get started with GitLab, here's a list of recommended next steps.

Already a pro? Just edit this README.md and make it your own. Want to make it easy? [Use the template at the bottom](#editing-this-readme)!

## Add your files

* [Create](https://docs.gitlab.com/ee/user/project/repository/web_editor.html#create-a-file) or [upload](https://docs.gitlab.com/ee/user/project/repository/web_editor.html#upload-a-file) files
* [Add files using the command line](https://docs.gitlab.com/topics/git/add_files/#add-files-to-a-git-repository) or push an existing Git repository with the following command:

```
cd existing_repo
git remote add origin https://g.teches.link/chaint/java-tron/java-tron-evnet.git
git branch -M main
git push -uf origin main
```

## Integrate with your tools

* [Set up project integrations](https://g.teches.link/chaint/java-tron/java-tron-evnet/-/settings/integrations)

## Collaborate with your team

* [Invite team members and collaborators](https://docs.gitlab.com/ee/user/project/members/)
* [Create a new merge request](https://docs.gitlab.com/ee/user/project/merge_requests/creating_merge_requests.html)
* [Automatically close issues from merge requests](https://docs.gitlab.com/ee/user/project/issues/managing_issues.html#closing-issues-automatically)
* [Enable merge request approvals](https://docs.gitlab.com/ee/user/project/merge_requests/approvals/)
* [Set auto-merge](https://docs.gitlab.com/user/project/merge_requests/auto_merge/)

## Test and Deploy

Use the built-in continuous integration in GitLab.

* [Get started with GitLab CI/CD](https://docs.gitlab.com/ee/ci/quick_start/)
* [Analyze your code for known vulnerabilities with Static Application Security Testing (SAST)](https://docs.gitlab.com/ee/user/application_security/sast/)
* [Deploy to Kubernetes, Amazon EC2, or Amazon ECS using Auto Deploy](https://docs.gitlab.com/ee/topics/autodevops/requirements.html)
* [Use pull-based deployments for improved Kubernetes management](https://docs.gitlab.com/ee/user/clusters/agent/)
* [Set up protected environments](https://docs.gitlab.com/ee/ci/environments/protected_environments.html)

***

# Editing this README

When you're ready to make this README your own, just edit this file and use the handy template below (or feel free to structure it however you want - this is just a starting point!). Thanks to [makeareadme.com](https://www.makeareadme.com/) for this template.

## Suggestions for a good README

Every project is different, so consider which of these sections apply to yours. The sections used in the template are suggestions for most open source projects. Also keep in mind that while a README can be too long and detailed, too long is better than too short. If you think your README is too long, consider utilizing another form of documentation rather than cutting out information.

## Name
Choose a self-explaining name for your project.

## Description
Let people know what your project can do specifically. Provide context and add a link to any reference visitors might be unfamiliar with. A list of Features or a Background subsection can also be added here. If there are alternatives to your project, this is a good place to list differentiating factors.

## Badges
On some READMEs, you may see small images that convey metadata, such as whether or not all the tests are passing for the project. You can use Shields to add some to your README. Many services also have instructions for adding a badge.

## Visuals
Depending on what you are making, it can be a good idea to include screenshots or even a video (you'll frequently see GIFs rather than actual videos). Tools like ttygif can help, but check out Asciinema for a more sophisticated method.

## Installation
Within a particular ecosystem, there may be a common way of installing things, such as using Yarn, NuGet, or Homebrew. However, consider the possibility that whoever is reading your README is a novice and would like more guidance. Listing specific steps helps remove ambiguity and gets people to using your project as quickly as possible. If it only runs in a specific context like a particular programming language version or operating system or has dependencies that have to be installed manually, also add a Requirements subsection.

## Usage
Use examples liberally, and show the expected output if you can. It's helpful to have inline the smallest example of usage that you can demonstrate, while providing links to more sophisticated examples if they are too long to reasonably include in the README.

## Support
Tell people where they can go to for help. It can be any combination of an issue tracker, a chat room, an email address, etc.

## Roadmap
If you have ideas for releases in the future, it is a good idea to list them in the README.

## Contributing
State if you are open to contributions and what your requirements are for accepting them.

For people who want to make changes to your project, it's helpful to have some documentation on how to get started. Perhaps there is a script that they should run or some environment variables that they need to set. Make these steps explicit. These instructions could also be useful to your future self.

You can also document commands to lint the code or run tests. These steps help to ensure high code quality and reduce the likelihood that the changes inadvertently break something. Having instructions for running tests is especially helpful if it requires external setup, such as starting a Selenium server for testing in a browser.

## Authors and acknowledgment
Show your appreciation to those who have contributed to the project.

## License
For open source projects, say how it is licensed.

## Project status
If you have run out of energy or time for your project, put a note at the top of the README saying that development has slowed down or stopped completely. Someone may choose to fork your project or volunteer to step in as a maintainer or owner, allowing your project to keep going. You can also make an explicit request for maintainers.
