import { imageRepositories } from './docker-images.mjs';
import { root } from './process.mjs';

const legacyTag = /^codecraft\/(?:java|sandbox):local-[0-9a-f]{24}$|^codecraft\/cart-total:(?:(?:validation|execution-test)-[0-9a-f]{12}|contract-red)$/;
const currentTag = /^codecraft\/(?:java|sandbox|cart-total):local-[0-9a-f]{64}$/;

// Work only on our known build tags, never global dangling images or foreign aliases.
export function cleanupPlan(images, protectedIds, protectedTags = new Set()) {
  const plan = [];
  for (const image of images) {
    const tags = image.RepoTags ?? [];
    if (!tags.length || image.RepoDigests?.length || !tags.every((tag) => legacyTag.test(tag) || currentTag.test(tag))) continue;
    if (!protectedIds.has(image.Id)) {
      plan.push({ id: image.Id, tags, removeImage: true });
    } else if (tags.some((tag) => currentTag.test(tag))) {
      const obsolete = tags.filter((tag) => legacyTag.test(tag) && !protectedTags.has(tag));
      if (obsolete.length) plan.push({ id: image.Id, tags: obsolete, removeImage: false });
    }
  }
  return plan;
}

export function containerPublicationPath(container, image) {
  const references = [container.Config?.Image ?? '', ...(image.RepoTags ?? [])];
  const mounts = container.Mounts ?? [];
  const labels = container.Config?.Labels ?? {};
  const workingDirectory = (labels['com.docker.compose.project.working_dir'] ?? '').replaceAll('\\', '/').replace(/\/$/, '');
  const ownCompose = workingDirectory === root.replaceAll('\\', '/').replace(/\/$/, '');
  if (references.some((tag) => tag.startsWith('codecraft/java:'))
    || mounts.some((mount) => mount.Destination === '/app/publication.json')
    || (ownCompose && labels['com.docker.compose.service'] === 'java')) return '/app/publication.json';
  if (references.some((tag) => tag.startsWith('codecraft/sandbox:'))
    || mounts.some((mount) => mount.Destination === '/publications.json')
    || (ownCompose && labels['com.docker.compose.service'] === 'sandbox')) return '/publications.json';
  return null;
}

export function publicationImageIds(value) {
  if (Array.isArray(value)) return value.flatMap(publicationImageIds);
  if (value && !Object.keys(value).length) return [];
  const id = value?.identity?.execution_image_digest;
  if (!/^sha256:[0-9a-f]{64}$/.test(id ?? '')) throw new Error('Publication has no valid execution image ID; refusing cleanup');
  return [id];
}

export function validateCurrentReference(value) {
  if (!imageRepositories.includes(value.repository) || !/^sha256:[0-9a-f]{64}$/.test(value.id ?? '')) {
    throw new Error('Invalid current image reference; refusing cleanup');
  }
  return value.id;
}
