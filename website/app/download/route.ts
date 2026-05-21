import { NextResponse } from 'next/server'

/**
 * Direct-download handler for the APK.
 *
 * The Pocket Node Play Store listing is in preparation; until it goes
 * live the only install path is sideloading the latest GitHub Release.
 * We don't want the Download APK button to dump people on the GitHub
 * release page (which is noisy and requires them to scroll to find the
 * .apk asset), so this route resolves the latest release at request
 * time and 302-redirects to the .apk asset directly.
 *
 * Why a Route Handler (and not a static link to a versioned URL):
 *   - The release artifact name encodes the version (e.g.
 *     `PocketNode-v1.6.1.apk`), so a static link would go stale on
 *     every release.
 *   - GitHub's `/releases/latest/download/<name>` redirect requires
 *     the asset filename to be stable across releases. Ours isn't.
 *   - This handler asks the GitHub API for the current latest, picks
 *     out the .apk asset, and returns its `browser_download_url`.
 *
 * Caching:
 *   The redirect itself is cached at the Vercel edge for 10 minutes
 *   with stale-while-revalidate of 1 hour, so the GitHub API quota
 *   (60/hour anonymous) is not a bottleneck even during a launch
 *   spike. New releases propagate within the cache window.
 */

const REPO_OWNER = 'RaheemJnr'
const REPO_NAME = 'pocket-node'
const RELEASES_API_URL = `https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/releases/latest`
const FALLBACK_URL = `https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/latest`

interface ReleaseAsset {
  name: string
  browser_download_url: string
}

interface LatestRelease {
  assets?: ReleaseAsset[]
}

export const dynamic = 'force-dynamic'

export async function GET() {
  try {
    const response = await fetch(RELEASES_API_URL, {
      headers: {
        Accept: 'application/vnd.github+json',
        // GitHub asks for a unique User-Agent on API requests so they
        // can identify high-volume callers. The handler runs server-
        // side on Vercel, so the user's browser never sees this.
        'User-Agent': 'pocket-node-website-download-handler',
      },
      // Cache the upstream API response too, so this handler doesn't
      // hammer api.github.com inside a single edge cache window.
      next: { revalidate: 600 },
    })

    if (!response.ok) {
      throw new Error(`GitHub API responded ${response.status}`)
    }

    const release = (await response.json()) as LatestRelease
    const apkAsset = release.assets?.find((asset) => asset.name.endsWith('.apk'))

    if (!apkAsset) {
      throw new Error('Latest release has no .apk asset')
    }

    return NextResponse.redirect(apkAsset.browser_download_url, {
      status: 302,
      headers: {
        // Vercel edge cache for 10 minutes; can serve stale for an
        // extra hour while revalidating in the background. This means
        // new releases propagate inside ~10 minutes worst case, with
        // no user ever seeing a hard error during the revalidation.
        'Cache-Control': 'public, s-maxage=600, stale-while-revalidate=3600',
      },
    })
  } catch (error) {
    // If anything fails (rate limit, network blip, malformed response)
    // we send the user to the GitHub release page rather than show
    // them a 500. They can grab the .apk by hand from there.
    console.error('Failed to resolve latest APK asset:', error)
    return NextResponse.redirect(FALLBACK_URL, { status: 302 })
  }
}
