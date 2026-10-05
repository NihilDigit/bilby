#!/usr/bin/env bash
# 应用内差分更新的附件:新版本 M.m.p 为已公开的 M.(m-1).x 与 M.m.x 各出一份
# bilby-windows-<架构>-<新版本>-from-<旧版本>.zip(架构为 x64 与 arm64,各对各的旧版),里面是补丁包每个文件以旧版对应文件为前缀字典的
# zstd 差分(--patch-from),客户端的还原见 shared/src/desktopMain/kotlin/dev/bilby/update/ZstdPatch.kt。
#
# 对应文件默认是同一路径。jar 名里的哈希取自 jar 的内容,Bilby 自己的 shared-desktop-<哈希>.jar
# 每次构建都换名,同一路径找不到时按去掉哈希的名字在旧版里找唯一的一个,另写一个 <路径>.base
# 告诉客户端拿哪个文件作字典。旧版里也找不到的文件按普通 zstd 压缩。
#
# 旧版清单里没有 zstd 的 DLL,说明它的客户端还不会用差分,跳过。跨度更大的升级走完整的 app.zip。
#
# 用法:delta-updates.sh <新版本> <dist 目录>。dist 里要已有新版的 -app.zip;需要 GH_TOKEN。
set -euo pipefail

version=$1
dist=$(cd "$2" && pwd)
work="${RUNNER_TEMP:-/tmp}/bilby-delta"
rm -rf "$work"

IFS=. read -r major minor _ <<< "$version"
bases=$(gh api --paginate "repos/$GITHUB_REPOSITORY/releases" \
    --jq '.[] | select(.draft | not) | select(.prerelease | not) | .tag_name | ltrimstr("v")' |
    awk -F. -v M="$major" -v m="$minor" -v self="$version" '$0 != self && $1 == M && ($2 == m || $2 == m - 1)')

# 两个架构都要有新版的补丁包:少了一个说明构建或汇总出了错,不能悄悄只给一个架构出差分。
arches="x64 arm64"
for arch in $arches; do
    mkdir -p "$work/new-$arch"
    unzip -q "$dist/bilby-windows-$arch-$version-app.zip" -d "$work/new-$arch"
done

# 旧版里与 $1 对应的文件,相对 $2 目录;没有则输出空。
# 同一路径优先;否则 jar 名去掉 -<哈希> 后在同一目录里找,恰好一个才算。
# 余下部分必须全是十六进制:app/desktop 的候选里不能混进 app/desktop-jvm-1.12.1-<哈希>.jar。
base_of() {
    local file=$1 old=$2
    if [ -f "$old/$file" ]; then
        echo "$file"
        return
    fi
    [[ $file =~ ^(.*)-[0-9a-f]+\.jar$ ]] || return 0
    local stem=${BASH_REMATCH[1]} found="" candidate
    for candidate in "$old/$stem"-*.jar; do
        [ -f "$candidate" ] || continue
        [[ ${candidate#"$old/$stem"-} =~ ^[0-9a-f]+\.jar$ ]] || continue
        [ -z "$found" ] || return 0
        found=${candidate#"$old/"}
    done
    echo "$found"
}

delta() {
    local arch=$1 base=$2
    local prefix="bilby-windows-$arch"
    local dir="$work/$arch-$base"
    local new="$work/new-$arch"
    local out="$dist/$prefix-$version-from-$base.zip"
    mkdir -p "$dir/old" "$dir/out"
    # arm64 从首个带它的版本起才有,更早的版本下载不到,跳过即可
    if ! gh release download "v$base" --repo "$GITHUB_REPOSITORY" --dir "$dir" \
        -p "$prefix-$base-files.json" -p "$prefix-$base-app.zip"; then
        echo "v$base 没有 $arch 的补丁包,跳过"
        return 0
    fi
    if ! grep -q '"app/resources/zstd/' "$dir/$prefix-$base-files.json"; then
        echo "v$base 的客户端不会用差分,跳过"
        return 0
    fi
    unzip -q "$dir/$prefix-$base-app.zip" -d "$dir/old"
    (cd "$new" && find . -type f -printf '%P\n') | while read -r file; do
        mkdir -p "$(dirname "$dir/out/$file")"
        local from
        from=$(base_of "$file" "$dir/old")
        # --long 开长距离匹配,旧文件里隔得很远的相同段也能引用。文件大过 2^27 时 CLI 自行加大窗口;
        # 客户端用 ZSTD_decompressDCtx 单次解压,不受流式解压的窗口上限约束(x64 以 172 MB、ARM64 以
        # 160 MB 的文件验证过)
        if [ -n "$from" ]; then
            zstd -19 --long=27 -q --patch-from="$dir/old/$from" "$new/$file" -o "$dir/out/$file.zst"
            [ "$from" = "$file" ] || printf '%s' "$from" > "$dir/out/$file.base"
        else
            zstd -19 -q "$new/$file" -o "$dir/out/$file.zst"
        fi
    done
    # 已是 zstd,不再压缩;-D 不写目录条目
    (cd "$dir/out" && zip -q -r -D -0 "$out" .)
    echo "v$base -> v$version ($arch): $(stat -c %s "$out") 字节"
}
export -f delta base_of
export version dist work

# 每份差分里 AOT 缓存那一个文件就要压约一分钟,按核数并行
for base in $bases; do
    for arch in $arches; do
        echo "$arch $base"
    done
done | xargs -r -P "$(nproc)" -L 1 bash -c 'delta "$0" "$1"'
