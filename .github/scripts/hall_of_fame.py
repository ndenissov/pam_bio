#!/usr/bin/env python3
"""
Fetch stargazers, forkers, and contributors from GitHub API
and update the Hall of Fame section in README.md.
"""

import os
import json
import urllib.request

REPO = "ndenissov/pam_bio"
README = "README.md"
START_MARKER = "<!-- HOF:START -->"
END_MARKER = "<!-- HOF:END -->"

def github_api(endpoint):
    """Fetch paginated results from GitHub API."""
    url = f"https://api.github.com{endpoint}"
    headers = {
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"

    items = []
    page = 1
    while True:
        sep = "&" if "?" in url else "?"
        req = urllib.request.Request(f"{url}{sep}per_page=100&page={page}", headers=headers)
        with urllib.request.urlopen(req) as resp:
            data = json.loads(resp.read())
        if not data:
            break
        items.extend(data)
        page += 1
        if len(data) < 100:
            break
    return items


def make_avatar(user, size=48):
    login = user["login"]
    avatar = user["avatar_url"]
    profile = user["html_url"]
    return f'<a href="{profile}"><img src="{avatar}" width="{size}" height="{size}" alt="@{login}" title="@{login}" style="border-radius:50%"></a>'


def main():
    stargazers = github_api(f"/repos/{REPO}/stargazers")
    forks = github_api(f"/repos/{REPO}/forks")
    contributors = github_api(f"/repos/{REPO}/contributors")

    # Deduplicate by login
    seen = set()
    all_users = []

    # Contributors first, then stargazers, then forkers
    for user in contributors:
        if user.get("type") == "Bot":
            continue
        login = user["login"]
        if login not in seen:
            seen.add(login)
            all_users.append(user)

    for user in stargazers:
        login = user["login"]
        if login not in seen:
            seen.add(login)
            all_users.append(user)

    for fork in forks:
        user = fork.get("owner", {})
        login = user.get("login", "")
        if login and login not in seen:
            seen.add(login)
            all_users.append(user)

    if not all_users:
        hof_content = "*Be the first to star the repo and appear here!*"
    else:
        avatars = " ".join(make_avatar(u) for u in all_users[:100])
        hof_content = f"""\
<p align="center">
{avatars}
</p>

*{len(all_users)} amazing {'person' if len(all_users) == 1 else 'people'} — thank you!*"""

    # Read README and replace section
    with open(README, "r", encoding="utf-8") as f:
        content = f.read()

    start_idx = content.index(START_MARKER) + len(START_MARKER)
    end_idx = content.index(END_MARKER)

    new_content = content[:start_idx] + "\n" + hof_content + "\n" + content[end_idx:]

    with open(README, "w", encoding="utf-8") as f:
        f.write(new_content)

    print(f"Updated Hall of Fame with {len(all_users)} users.")


if __name__ == "__main__":
    main()
