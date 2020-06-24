# Github Stats CLI - tool for ranking an organization's repositories

## Requirements

1. Install JDK 12 (tested on 12 only but may work on other versions).
2. Setup a personal access OAUTH token on your github.com account.  See [here](https://developer.github.com/v4/guides/forming-calls/#authenticating-with-graphql)

## Usage

1. Clone the repo
1. `./gradlew build`
1. Run wrapper script `./github-stats`.  E.g.:

```
$ GITHUB_OAUTH_TOKEN=<redacted> ./github-stats --contrib-pct --N 10 --org google
repo                                         contribPct
-------------------------------------------------------
python-spanner-orm                              3250.00
ggrc-core                                       3225.51
tock-on-titan                                   2700.00
amber                                           2260.00
DirectXShaderCompiler                           1909.09
dokka                                           1766.67
copr-sundry                                     1745.45
node-dependency-analysis                        1550.00
pprof-nodejs                                    1475.00
fleetspeak                                      1206.67

```
1. Run `./github-stats --help` for more info.

## Notes

* Github API reports true fork count, but public website shows fork count of root repo (which may be greater).
* Github API has [rate limits](https://developer.github.com/v4/guides/resource-limitations/).  As it stands the CLI needs to make API calls which ultimately address the total number of repositories in an organization.
