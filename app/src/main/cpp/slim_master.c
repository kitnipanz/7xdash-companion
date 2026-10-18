/*
 * 7xDash: slim a YouTube HLS master playlist before the receiver fetches its media playlists.
 *
 * UxPlay fetches every media playlist through the iPhone (one FCUP round trip each, ~0.6 s)
 * before it lets the player start. YouTube lists ~19: H.264 and VP9 at 144p-1080p plus two
 * audio qualities per language. The car only needs H.264 at 360p-1080p and the audio those
 * use, in every language (the player follows the track picked on the iPhone).
 *
 * Kept: #EXT-X-STREAM-INF variants with an avc1 codec and a height of 360-1080 (and the URI
 * line after each), #EXT-X-MEDIA renditions of the audio groups those variants use, all
 * subtitle / caption renditions and other tags. Dropped: other codecs, other sizes,
 * #EXT-X-I-FRAME-STREAM-INF (trick play, unused). If nothing would be left, the playlist is
 * returned unchanged.
 *
 * GPL-3.0, like the rest of the receiver.
 */

#include "slim_master.h"

#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_LINES 512
#define MAX_GROUPS 32

static bool starts_with(const char *line, const char *prefix) {
    return strncmp(line, prefix, strlen(prefix)) == 0;
}

/* attribute value of key= in an HLS tag line, quoted or not; copies at most out_len-1 chars */
static bool attr(const char *line, const char *key, char *out, size_t out_len) {
    const char *p = line;
    size_t klen = strlen(key);
    while ((p = strstr(p, key)) != NULL) {
        /* must start an attribute: after ':' or ',' */
        if (p > line && (p[-1] == ':' || p[-1] == ',') && p[klen] == '=') {
            p += klen + 1;
            size_t n = 0;
            if (*p == '"') {
                p++;
                while (p[n] && p[n] != '"' && p[n] != '\n' && p[n] != '\r') n++;
            } else {
                while (p[n] && p[n] != ',' && p[n] != '\n' && p[n] != '\r') n++;
            }
            if (n >= out_len) n = out_len - 1;
            memcpy(out, p, n);
            out[n] = '\0';
            return true;
        }
        p += klen;
    }
    return false;
}

static bool wanted_variant(const char *line) {
    char codecs[256] = {0};
    char resolution[32] = {0};
    if (!attr(line, "CODECS", codecs, sizeof(codecs)) || !strstr(codecs, "avc1")) {
        return false;
    }
    if (attr(line, "RESOLUTION", resolution, sizeof(resolution))) {
        const char *x = strchr(resolution, 'x');
        int height = x ? atoi(x + 1) : 0;
        if (height < 360 || height > 1080) {
            return false;
        }
    }
    return true;
}

char *slim_master_playlist(char *master, int *variants_before, int *variants_after,
                           int *renditions_before, int *renditions_after) {
    *variants_before = *variants_after = *renditions_before = *renditions_after = 0;
    if (!master) return master;

    /* split into lines (pointers into a copy) */
    char *copy = strdup(master);
    if (!copy) return master;
    char *lines[MAX_LINES];
    int n = 0;
    for (char *save = NULL, *l = strtok_r(copy, "\n", &save); l && n < MAX_LINES;
         l = strtok_r(NULL, "\n", &save)) {
        size_t len = strlen(l);
        if (len && l[len - 1] == '\r') l[len - 1] = '\0';
        lines[n++] = l;
    }
    if (n == MAX_LINES) {
        free(copy);
        return master; /* unexpectedly long: leave it alone */
    }

    bool keep[MAX_LINES];
    char groups[MAX_GROUPS][64];
    int ngroups = 0;

    /* pass 1: variants (tag line + its URI line) */
    for (int i = 0; i < n; i++) keep[i] = true;
    for (int i = 0; i < n; i++) {
        if (starts_with(lines[i], "#EXT-X-I-FRAME-STREAM-INF")) {
            keep[i] = false;
        } else if (starts_with(lines[i], "#EXT-X-STREAM-INF")) {
            (*variants_before)++;
            bool want = wanted_variant(lines[i]);
            keep[i] = want;
            if (i + 1 < n && lines[i + 1][0] != '#') keep[i + 1] = want;
            if (want) {
                (*variants_after)++;
                char group[64];
                if (attr(lines[i], "AUDIO", group, sizeof(group))) {
                    bool known = false;
                    for (int g = 0; g < ngroups; g++) known |= strcmp(groups[g], group) == 0;
                    if (!known && ngroups < MAX_GROUPS) strcpy(groups[ngroups++], group);
                }
            }
        }
    }
    if (*variants_after == 0) {
        free(copy);
        *variants_after = *variants_before;
        return master; /* nothing usable: let the player have everything */
    }

    /* pass 2: audio renditions only for the groups still in use */
    for (int i = 0; i < n; i++) {
        if (!starts_with(lines[i], "#EXT-X-MEDIA:")) continue;
        char type[32] = {0};
        attr(lines[i], "TYPE", type, sizeof(type));
        if (strcmp(type, "AUDIO") != 0) continue;
        (*renditions_before)++;
        char group[64] = {0};
        attr(lines[i], "GROUP-ID", group, sizeof(group));
        bool used = false;
        for (int g = 0; g < ngroups; g++) used |= strcmp(groups[g], group) == 0;
        keep[i] = used;
        if (used) (*renditions_after)++;
    }

    size_t total = 1;
    for (int i = 0; i < n; i++) if (keep[i]) total += strlen(lines[i]) + 1;
    char *out = malloc(total);
    if (!out) {
        free(copy);
        return master;
    }
    char *w = out;
    for (int i = 0; i < n; i++) {
        if (!keep[i]) continue;
        size_t len = strlen(lines[i]);
        memcpy(w, lines[i], len);
        w += len;
        *w++ = '\n';
    }
    *w = '\0';
    free(copy);
    free(master);
    return out;
}
